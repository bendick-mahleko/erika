package de.erika.billing

import de.erika.identity.InvitationAccepted
import de.erika.identity.MemberRemoved
import de.erika.identity.MemberRoleChanged
import de.erika.identity.WorkspaceContacts
import de.erika.platform.audit.AuditableEvent
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.web.ErikaException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a workspace may do with an add-on (ADR 0043). */
enum class AddonState { DISABLED, NONE, TRIAL, ACTIVE, EXEMPT, READ_ONLY }

internal data class AddonRow(
    val workspaceId: UUID,
    val trialEndsAt: OffsetDateTime?,
    val exempt: Boolean,
    val active: Boolean,
    val pending: String?,
    val itemId: String?,
    val seatItemId: String?,
    val seatsBilled: Int,
    val noticeAcceptedAt: OffsetDateTime?,
)

/** The add-on as the billing page shows it. */
internal data class MaraAddonView(
    val offered: Boolean,
    val state: AddonState,
    val row: AddonRow?,
    val seatsCounted: Int,
    val seatsIncluded: Int,
    val plan: BillingPlan,
)

/**
 * The rules (ADR 0043). DISABLED: billing is off or the add-on has no prices here, so MARA is
 * free. A read-only ERIKA workspace is read-only for MARA too. Otherwise: exempt (beta firms) or
 * an exempt workspace → EXEMPT; the add-on on a running subscription → ACTIVE; a trial that
 * hasn't ended → TRIAL; started once and neither → READ_ONLY; never started → NONE.
 */
internal object AddonRules {
    fun state(
        row: AddonRow?,
        erika: BillingState,
        offered: Boolean,
        subscriptionStatus: String?,
        now: Instant = Instant.now(),
    ): AddonState =
        when {
            erika == BillingState.DISABLED || !offered -> AddonState.DISABLED
            erika == BillingState.READ_ONLY -> AddonState.READ_ONLY
            erika == BillingState.EXEMPT || row?.exempt == true -> AddonState.EXEMPT
            row == null -> AddonState.NONE
            row.active && subscriptionStatus in setOf("active", "trialing", "past_due") -> AddonState.ACTIVE
            row.trialEndsAt != null && now.isBefore(row.trialEndsAt.toInstant()) -> AddonState.TRIAL
            else -> AddonState.READ_ONLY
        }

    fun writable(state: AddonState) = state in setOf(AddonState.DISABLED, AddonState.TRIAL, AddonState.ACTIVE, AddonState.EXEMPT)

    /** Extra seats: everyone above the included ones. */
    fun extraSeats(
        counted: Int,
        included: Int,
    ) = (counted - included).coerceAtLeast(0)
}

/**
 * Published API of `billing` for the MARA modules: may this workspace use MARA (MARA Stage 10)?
 * Runs in the caller's tenant transaction.
 */
@Service
class MaraEntitlement internal constructor(
    private val addons: MaraAddons,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun state(workspaceId: UUID): AddonState = addons.state(workspaceId)

    /** 403 `MARA_NOT_BOOKED` or `MARA_READ_ONLY` unless the workspace may save MARA data. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireWritable(workspaceId: UUID) {
        when (val state = state(workspaceId)) {
            AddonState.NONE -> {
                throw ErikaException(
                    "MARA_NOT_BOOKED",
                    HttpStatus.FORBIDDEN,
                    "MARA isn't booked. The owner can start the free trial on the billing page.",
                )
            }

            else -> {
                if (!AddonRules.writable(state)) {
                    throw ErikaException(
                        "MARA_READ_ONLY",
                        HttpStatus.FORBIDDEN,
                        "MARA is read-only: the trial has ended or the add-on was cancelled. Data can still be read and exported.",
                    )
                }
            }
        }
    }
}

/** The MARA add-on: trial, booking and seats (M-AC-02, ADR 0043). */
@Service
internal class MaraAddons(
    private val props: BillingProperties,
    private val repo: AddonRepository,
    private val accounts: BillingAccountRepository,
    private val contacts: WorkspaceContacts,
    private val stripe: StripeClient,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
) {
    val offered: Boolean get() = props.priceMaraMonthly.isNotBlank() || props.priceMaraAnnual.isNotBlank()

    @Transactional(propagation = Propagation.MANDATORY)
    fun state(workspaceId: UUID): AddonState {
        val account = accounts.find(workspaceId)
        return AddonRules.state(repo.find(workspaceId), Entitlements.state(account, props), offered, account?.subscriptionStatus)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun view(workspaceId: UUID): MaraAddonView {
        val account = accounts.find(workspaceId)
        val row = repo.find(workspaceId)
        return MaraAddonView(
            offered = offered,
            state = AddonRules.state(row, Entitlements.state(account, props), offered, account?.subscriptionStatus),
            row = row,
            seatsCounted = seats(),
            seatsIncluded = props.maraIncludedSeats,
            plan = if (account?.planInterval == "year") BillingPlan.ANNUAL else BillingPlan.MONTHLY,
        )
    }

    /** Net prices of the add-on and a seat for [plan], from Stripe (cached); nulls if unknown. */
    fun prices(plan: BillingPlan): Triple<Long?, Long?, String?> {
        if (!props.enabled || props.stripeSecretKey.isBlank()) return Triple(null, null, null)
        val base = priceOf(plan)?.let(stripe::price)
        val seat = seatPriceOf(plan)?.let(stripe::price)
        return Triple(
            base?.path("unit_amount")?.takeIf { it.isNumber }?.asLong(),
            seat?.path("unit_amount")?.takeIf { it.isNumber }?.asLong(),
            base?.path("currency")?.asString()?.uppercase(),
        )
    }

    /** Owners, members and technicians count (ADR 0039); tax advisors are free. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun seats(): Int = contacts.workforce().size

    /** Starts the 30-day trial once, with the works-council confirmation (M-BR-09). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun startTrial(
        workspaceId: UUID,
        actorId: UUID,
        noticeAccepted: Boolean,
    ) {
        requireNotice(noticeAccepted)
        val row = repo.find(workspaceId)
        if (row?.trialEndsAt != null || row?.active == true) {
            throw ErikaException("MARA_TRIAL_USED", HttpStatus.CONFLICT, "The MARA trial was used already; book the add-on to continue.")
        }
        val ends = Instant.now().plus(Duration.ofDays(props.maraTrialDays))
        repo.startTrial(workspaceId, ends, actorId)
        events.publishEvent(MaraTrialStarted(workspaceId, actorId, ends))
    }

    /** Adds the add-on to the running subscription, through the worker. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun book(
        workspaceId: UUID,
        actorId: UUID,
        noticeAccepted: Boolean,
    ): UUID {
        requireOffered()
        requireNotice(noticeAccepted)
        val account = accounts.find(workspaceId)
        if (account?.subscriptionId == null || account.subscriptionStatus !in setOf("active", "trialing", "past_due")) {
            throw ErikaException(
                "SUBSCRIPTION_REQUIRED",
                HttpStatus.CONFLICT,
                "Book MARA together with a plan in the checkout; there is no running subscription.",
            )
        }
        val row = repo.find(workspaceId)
        if (row?.active == true && row.pending == null) {
            throw ErikaException("MARA_ALREADY_BOOKED", HttpStatus.CONFLICT, "MARA is already on the subscription.")
        }
        repo.ensure(workspaceId)
        repo.acceptNotice(workspaceId, actorId)
        repo.setPending(workspaceId, "BOOKING")
        val job = jobs.enqueue(MaraItemsJobHandler.TYPE, MaraItemsJobHandler.Payload(MaraItemsJobHandler.ADD), workspaceId)
        events.publishEvent(MaraAddonChangeRequested(workspaceId, actorId, "book"))
        return job
    }

    /** Removes the add-on and its seats, through the worker. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun cancel(
        workspaceId: UUID,
        actorId: UUID,
    ) {
        val row = repo.find(workspaceId)
        if (row == null || (!row.active && row.itemId == null)) {
            throw ErikaException("MARA_NOT_BOOKED", HttpStatus.CONFLICT, "MARA isn't on the subscription.")
        }
        repo.setPending(workspaceId, "CANCELLING")
        jobs.enqueue(MaraItemsJobHandler.TYPE, MaraItemsJobHandler.Payload(MaraItemsJobHandler.REMOVE), workspaceId)
        events.publishEvent(MaraAddonChangeRequested(workspaceId, actorId, "cancel"))
    }

    /** The checkout's extra line items for MARA ([plan]'s interval); requires the confirmation. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun checkoutItems(
        workspaceId: UUID,
        actorId: UUID,
        plan: BillingPlan,
        noticeAccepted: Boolean,
    ): List<Pair<String, Int>> {
        requireOffered()
        if (!noticeAccepted && repo.find(workspaceId)?.noticeAcceptedAt == null) throw noticeRequired()
        repo.ensure(workspaceId)
        repo.acceptNotice(workspaceId, actorId)
        val base = priceOf(plan) ?: throw notOffered()
        val extra = AddonRules.extraSeats(seats(), props.maraIncludedSeats)
        return buildList {
            add(base to 1)
            if (extra > 0) add((seatPriceOf(plan) ?: throw notOffered()) to extra)
        }
    }

    fun priceOf(plan: BillingPlan) =
        when (plan) {
            BillingPlan.MONTHLY -> props.priceMaraMonthly
            BillingPlan.ANNUAL -> props.priceMaraAnnual
        }.takeIf { it.isNotBlank() }

    fun seatPriceOf(plan: BillingPlan) =
        when (plan) {
            BillingPlan.MONTHLY -> props.priceMaraSeatMonthly
            BillingPlan.ANNUAL -> props.priceMaraSeatAnnual
        }.takeIf { it.isNotBlank() }

    fun isMaraPrice(priceId: String?) =
        priceId != null && priceId.isNotBlank() && priceId in setOf(props.priceMaraMonthly, props.priceMaraAnnual)

    fun isSeatPrice(priceId: String?) =
        priceId != null && priceId.isNotBlank() && priceId in setOf(props.priceMaraSeatMonthly, props.priceMaraSeatAnnual)

    /** Seat changes (someone joined, left or changed role): the worker adjusts the seat item. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: InvitationAccepted) = seatsChanged(event.workspaceId)

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: MemberRemoved) = seatsChanged(event.workspaceId)

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: MemberRoleChanged) = seatsChanged(event.workspaceId)

    private fun seatsChanged(workspaceId: UUID) {
        val row = repo.find(workspaceId) ?: return
        if (row.itemId == null || !props.enabled) return
        jobs.enqueue(MaraSeatsJobHandler.TYPE, MaraSeatsJobHandler.Payload(), workspaceId)
    }

    private fun requireNotice(accepted: Boolean) {
        if (!accepted) throw noticeRequired()
    }

    private fun requireOffered() {
        if (!props.enabled || !offered) throw notOffered()
    }

    private fun noticeRequired() =
        ErikaException(
            "WORKS_COUNCIL_NOTICE_REQUIRED",
            HttpStatus.BAD_REQUEST,
            "Confirm the notice on the works council and the information for employees first.",
        )

    private fun notOffered() = ErikaException("MARA_NOT_OFFERED", HttpStatus.CONFLICT, "The MARA add-on isn't offered here.")
}

/**
 * Adds (with the seats) or removes the MARA items on the subscription. Idempotent: an item that
 * exists isn't added twice (Stripe idempotency key per subscription), a missing one counts as
 * removed. Stripe prorates (`create_prorations`), so a change mid-period is billed for its days.
 */
@Component
internal class MaraItemsJobHandler(
    private val repo: AddonRepository,
    private val accounts: BillingAccountRepository,
    private val addons: MaraAddons,
    private val seats: MaraSeatsJobHandler,
    private val stripe: StripeClient,
) : JobHandler<MaraItemsJobHandler.Payload> {
    data class Payload(
        val action: String,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val workspaceId = checkNotNull(context.workspaceId)
        val account = accounts.find(workspaceId) ?: return
        val row = repo.find(workspaceId) ?: return
        when (payload.action) {
            ADD -> {
                val subscription = account.subscriptionId ?: return repo.setPending(workspaceId, null)
                val plan = if (account.planInterval == "year") BillingPlan.ANNUAL else BillingPlan.MONTHLY
                val itemId =
                    row.itemId ?: stripe
                        .createSubscriptionItem(
                            mapOf(
                                "subscription" to subscription,
                                "price" to checkNotNull(addons.priceOf(plan)),
                                "quantity" to "1",
                                "proration_behavior" to PRORATE,
                                "metadata[workspace_id]" to workspaceId.toString(),
                            ),
                            "mara-item-$subscription",
                        ).path("id")
                        .asString()
                        .ifEmpty { throw StripeException(502, null, "Subscription item without ID") }
                repo.setItems(workspaceId, itemId, row.seatItemId, row.seatsBilled, active = true, pending = null)
                seats.adjust(workspaceId)
            }

            REMOVE -> {
                row.seatItemId?.let { stripe.deleteSubscriptionItem(it, PRORATE) }
                row.itemId?.let { stripe.deleteSubscriptionItem(it, PRORATE) }
                repo.setItems(workspaceId, null, null, 0, active = false, pending = null)
                repo.endTrial(workspaceId)
            }
        }
    }

    companion object {
        const val TYPE = "billing.mara-items"
        const val ADD = "ADD"
        const val REMOVE = "REMOVE"
        const val PRORATE = "create_prorations"
    }
}

/** Sets the seat item to everyone above the included users (created, changed or removed). */
@Component
internal class MaraSeatsJobHandler(
    private val repo: AddonRepository,
    private val accounts: BillingAccountRepository,
    private val addons: MaraAddons,
    private val props: BillingProperties,
    private val stripe: StripeClient,
) : JobHandler<MaraSeatsJobHandler.Payload> {
    class Payload

    private val log = LoggerFactory.getLogger(javaClass)

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) = adjust(checkNotNull(context.workspaceId))

    fun adjust(workspaceId: UUID) {
        val row = repo.find(workspaceId) ?: return
        val account = accounts.find(workspaceId) ?: return
        val subscription = account.subscriptionId ?: return
        if (row.itemId == null) return
        val extra = AddonRules.extraSeats(addons.seats(), props.maraIncludedSeats)
        if (extra == row.seatsBilled && (extra == 0) == (row.seatItemId == null)) return
        val plan = if (account.planInterval == "year") BillingPlan.ANNUAL else BillingPlan.MONTHLY
        val seatItem =
            when {
                extra == 0 -> {
                    row.seatItemId?.let { stripe.deleteSubscriptionItem(it, MaraItemsJobHandler.PRORATE) }
                    null
                }

                row.seatItemId == null -> {
                    stripe
                        .createSubscriptionItem(
                            mapOf(
                                "subscription" to subscription,
                                "price" to checkNotNull(addons.seatPriceOf(plan)),
                                "quantity" to extra.toString(),
                                "proration_behavior" to MaraItemsJobHandler.PRORATE,
                                "metadata[workspace_id]" to workspaceId.toString(),
                            ),
                            "mara-seats-$subscription-${row.itemId}",
                        ).path("id")
                        .asString()
                        .ifEmpty { throw StripeException(502, null, "Subscription item without ID") }
                }

                else -> {
                    stripe.updateSubscriptionItem(
                        row.seatItemId,
                        mapOf("quantity" to extra.toString(), "proration_behavior" to MaraItemsJobHandler.PRORATE),
                        "mara-seats-${row.seatItemId}-${row.seatsBilled}-$extra",
                    )
                    row.seatItemId
                }
            }
        log.info("MARA seats of workspace {}: {} extra (was {})", workspaceId, extra, row.seatsBilled)
        repo.setItems(workspaceId, row.itemId, seatItem, extra, active = row.active, pending = row.pending)
    }

    companion object {
        const val TYPE = "billing.mara-seats"
    }
}

@Repository
internal class AddonRepository(
    private val jdbc: JdbcClient,
) {
    fun find(workspaceId: UUID): AddonRow? =
        jdbc
            .sql("SELECT * FROM billing_addon WHERE workspace_id = :ws AND addon = 'MARA' AND workspace_id = current_workspace_id()")
            .param("ws", workspaceId)
            .query(::map)
            .optional()
            .orElse(null)

    fun ensure(workspaceId: UUID) {
        jdbc
            .sql("INSERT INTO billing_addon (workspace_id, addon) VALUES (:ws, 'MARA') ON CONFLICT DO NOTHING")
            .param("ws", workspaceId)
            .update()
    }

    fun startTrial(
        workspaceId: UUID,
        endsAt: Instant,
        actorId: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO billing_addon (workspace_id, addon, trial_ends_at, notice_accepted_at, notice_accepted_by)
                VALUES (:ws, 'MARA', :ends, now(), :actor)
                ON CONFLICT (workspace_id, addon) DO UPDATE SET trial_ends_at = EXCLUDED.trial_ends_at,
                    notice_accepted_at = EXCLUDED.notice_accepted_at, notice_accepted_by = EXCLUDED.notice_accepted_by, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("ends", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC))
            .param("actor", actorId)
            .update()
    }

    fun acceptNotice(
        workspaceId: UUID,
        actorId: UUID,
    ) {
        jdbc
            .sql(
                "UPDATE billing_addon SET notice_accepted_at = coalesce(notice_accepted_at, now()), " +
                    "notice_accepted_by = coalesce(notice_accepted_by, :actor), updated_at = now() WHERE workspace_id = :ws AND addon = 'MARA'",
            ).param("ws", workspaceId)
            .param("actor", actorId)
            .update()
    }

    fun setPending(
        workspaceId: UUID,
        pending: String?,
    ) {
        jdbc
            .sql("UPDATE billing_addon SET pending = :p, updated_at = now() WHERE workspace_id = :ws AND addon = 'MARA'")
            .param("ws", workspaceId)
            .param("p", pending)
            .update()
    }

    /** After a cancellation: the trial counts as used, so the add-on is read-only, not NONE. */
    fun endTrial(workspaceId: UUID) {
        jdbc
            .sql(
                "UPDATE billing_addon SET trial_ends_at = least(coalesce(trial_ends_at, now()), now()) WHERE workspace_id = :ws AND addon = 'MARA'",
            ).param("ws", workspaceId)
            .update()
    }

    fun setItems(
        workspaceId: UUID,
        itemId: String?,
        seatItemId: String?,
        seatsBilled: Int,
        active: Boolean,
        pending: String?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO billing_addon (workspace_id, addon, item_id, seat_item_id, seats_billed, active, pending)
                VALUES (:ws, 'MARA', :item, :seat, :billed, :active, :pending)
                ON CONFLICT (workspace_id, addon) DO UPDATE SET item_id = EXCLUDED.item_id, seat_item_id = EXCLUDED.seat_item_id,
                    seats_billed = EXCLUDED.seats_billed, active = EXCLUDED.active, pending = EXCLUDED.pending, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("item", itemId)
            .param("seat", seatItemId)
            .param("billed", seatsBilled)
            .param("active", active)
            .param("pending", pending)
            .update()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = AddonRow(
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        trialEndsAt = rs.getObject("trial_ends_at", OffsetDateTime::class.java),
        exempt = rs.getBoolean("exempt"),
        active = rs.getBoolean("active"),
        pending = rs.getString("pending"),
        itemId = rs.getString("item_id"),
        seatItemId = rs.getString("seat_item_id"),
        seatsBilled = rs.getInt("seats_billed"),
        noticeAcceptedAt = rs.getObject("notice_accepted_at", OffsetDateTime::class.java),
    )
}

// --- audit events (NF-10) -----------------------------------------------------------------

data class MaraTrialStarted(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val endsAt: Instant,
) : AuditableEvent {
    override val action = "billing.mara.trial_started"
    override val details get() = mapOf("endsAt" to endsAt.toString(), "worksCouncilNotice" to "accepted")
}

data class MaraAddonChangeRequested(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val change: String,
) : AuditableEvent {
    override val action = "billing.mara.$change"
}

/** Stripe confirmed the add-on's items (webhook). */
data class MaraAddonChanged(
    override val workspaceId: UUID,
    val active: Boolean,
    val seatsBilled: Int,
    val stripeEventId: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.mara.changed"
    override val details get() = mapOf("active" to active.toString(), "seats" to seatsBilled.toString(), "event" to stripeEventId)
}
