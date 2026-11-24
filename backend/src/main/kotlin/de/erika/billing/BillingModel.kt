package de.erika.billing

import de.erika.platform.audit.AuditableEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a workspace may do (EX-03). Only READ_ONLY limits anything. */
enum class BillingState { DISABLED, EXEMPT, TRIAL, ACTIVE, PAST_DUE, READ_ONLY }

enum class BillingPlan(
    val interval: String,
) {
    MONTHLY("month"),
    ANNUAL("year"),
}

internal data class BillingAccount(
    val workspaceId: UUID,
    val trialEndsAt: OffsetDateTime,
    val exempt: Boolean,
    val stripeCustomerId: String?,
    val subscriptionId: String?,
    val subscriptionStatus: String?,
    val priceId: String?,
    val planInterval: String?,
    val currentPeriodEnd: OffsetDateTime?,
    val cancelAtPeriodEnd: Boolean,
    val lastEventAt: OffsetDateTime?,
)

/** A subscription as Stripe reports it. */
internal data class SubscriptionSnapshot(
    val customerId: String,
    val subscriptionId: String,
    val status: String,
    val priceId: String?,
    val interval: String?,
    val currentPeriodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val eventCreated: Instant,
)

/**
 * The rules (ADR 0033): exempt workspaces and switched-off billing are never limited; a
 * subscription that is active, in a Stripe trial or being retried (past_due) allows everything;
 * an unpaid (dunning exhausted) or paused one makes the workspace read-only; otherwise (no
 * subscription, cancelled, incomplete) ERIKA's own trial decides, and after it read-only.
 */
object Entitlements {
    private val ACTIVE = setOf("active", "trialing")

    internal fun state(
        account: BillingAccount?,
        props: BillingProperties,
        now: Instant = Instant.now(),
    ): BillingState {
        if (!props.enabled) return BillingState.DISABLED
        if (account == null) return BillingState.TRIAL
        if (account.exempt || account.workspaceId == props.issuerWorkspaceId) return BillingState.EXEMPT
        return when (account.subscriptionStatus) {
            in ACTIVE -> BillingState.ACTIVE

            "past_due" -> BillingState.PAST_DUE

            // Stripe gave up collecting (dunning exhausted) or the subscription is paused: no trial fallback.
            "unpaid", "paused" -> BillingState.READ_ONLY

            else -> if (now.isBefore(account.trialEndsAt.toInstant())) BillingState.TRIAL else BillingState.READ_ONLY
        }
    }

    /** A subscription that still runs (then a second checkout is refused; the portal manages it). */
    internal fun running(status: String?) = status in setOf("active", "trialing", "past_due", "unpaid", "paused")
}

// --- audit events (NF-10); amounts and IDs only, never card or bank data -----------------

data class TrialStarted(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val endsAt: Instant,
) : AuditableEvent {
    override val action = "billing.trial.started"
    override val details get() = mapOf("endsAt" to endsAt.toString())
}

data class CheckoutStarted(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val plan: String,
) : AuditableEvent {
    override val action = "billing.checkout.started"
    override val details get() = mapOf("plan" to plan)
}

data class SubscriptionChanged(
    override val workspaceId: UUID,
    val subscriptionId: String,
    val from: String?,
    val to: String,
    val stripeEventId: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.subscription.changed"
    override val details
        get() = mapOf("subscription" to subscriptionId, "from" to (from ?: "none"), "to" to to, "event" to stripeEventId)
}

data class PaymentSucceeded(
    override val workspaceId: UUID,
    val stripeInvoiceId: String,
    val amount: String,
    val currency: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.payment.succeeded"
    override val details get() = mapOf("stripeInvoice" to stripeInvoiceId, "amount" to amount, "currency" to currency)
}

data class PaymentFailed(
    override val workspaceId: UUID,
    val stripeInvoiceId: String,
    val attempt: Int,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.payment.failed"
    override val details get() = mapOf("stripeInvoice" to stripeInvoiceId, "attempt" to attempt.toString())
}

data class SubscriptionCancelRequested(
    override val workspaceId: UUID,
    val subscriptionId: String,
    val reason: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.subscription.cancel_requested"
    override val details get() = mapOf("subscription" to subscriptionId, "reason" to reason)
}

/** ERIKA's own invoice for a paid Stripe invoice was queued (in the issuer's audit log). */
data class OwnInvoiceRequested(
    override val workspaceId: UUID,
    val customerWorkspaceId: UUID,
    val stripeInvoiceId: String,
    val invoiceId: UUID,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "billing.own_invoice.requested"
    override val details
        get() = mapOf("customer" to customerWorkspaceId.toString(), "stripeInvoice" to stripeInvoiceId, "invoiceId" to invoiceId.toString())
}

// --- repositories -------------------------------------------------------------------

@Repository
internal class BillingAccountRepository(
    private val jdbc: JdbcClient,
) {
    fun find(workspaceId: UUID): BillingAccount? =
        jdbc
            .sql("SELECT * FROM billing_account WHERE workspace_id = :ws AND workspace_id = current_workspace_id()")
            .param("ws", workspaceId)
            .query(::map)
            .optional()
            .orElse(null)

    /** Starts the trial unless the workspace already has an account. Returns true if created. */
    fun startTrial(
        workspaceId: UUID,
        endsAt: Instant,
    ): Boolean =
        jdbc
            .sql("INSERT INTO billing_account (workspace_id, trial_ends_at) VALUES (:ws, :ends) ON CONFLICT (workspace_id) DO NOTHING")
            .param("ws", workspaceId)
            .param("ends", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC))
            .update() == 1

    fun setCustomer(
        workspaceId: UUID,
        customerId: String,
    ) {
        jdbc
            .sql("UPDATE billing_account SET stripe_customer_id = :c, updated_at = now() WHERE workspace_id = :ws")
            .param("c", customerId)
            .param("ws", workspaceId)
            .update()
    }

    fun applySubscription(
        workspaceId: UUID,
        s: SubscriptionSnapshot,
    ) {
        jdbc
            .sql(
                """
                UPDATE billing_account
                   SET stripe_customer_id = coalesce(stripe_customer_id, :customer), subscription_id = :sub,
                       subscription_status = :status, price_id = :price, plan_interval = :interval,
                       current_period_end = :periodEnd, cancel_at_period_end = :cancel, last_event_at = :at, updated_at = now()
                 WHERE workspace_id = :ws
                """.trimIndent(),
            ).param("customer", s.customerId)
            .param("sub", s.subscriptionId)
            .param("status", s.status)
            .param("price", s.priceId)
            .param("interval", s.interval?.takeIf { it == "month" || it == "year" })
            .param("periodEnd", s.currentPeriodEnd?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
            .param("cancel", s.cancelAtPeriodEnd)
            .param("at", OffsetDateTime.ofInstant(s.eventCreated, ZoneOffset.UTC))
            .param("ws", workspaceId)
            .update()
    }

    /** The workspace of a Stripe customer (Stripe events carry no workspace). */
    fun workspaceForCustomer(customerId: String): UUID? =
        jdbc
            .sql("SELECT billing_workspace_for_customer(:c)")
            .param("c", customerId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = BillingAccount(
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        trialEndsAt = rs.getObject("trial_ends_at", OffsetDateTime::class.java),
        exempt = rs.getBoolean("exempt"),
        stripeCustomerId = rs.getString("stripe_customer_id"),
        subscriptionId = rs.getString("subscription_id"),
        subscriptionStatus = rs.getString("subscription_status"),
        priceId = rs.getString("price_id"),
        planInterval = rs.getString("plan_interval"),
        currentPeriodEnd = rs.getObject("current_period_end", OffsetDateTime::class.java),
        cancelAtPeriodEnd = rs.getBoolean("cancel_at_period_end"),
        lastEventAt = rs.getObject("last_event_at", OffsetDateTime::class.java),
    )
}

internal data class StoredStripeEvent(
    val id: String,
    val type: String,
    val created: Instant,
    val payload: String,
    val processed: Boolean,
)

/** System table `stripe_event` (no workspace): delivery deduplication and the worker's input. */
@Repository
internal class StripeEventRepository(
    private val jdbc: JdbcClient,
) {
    /** Stores the event; false if it was already received. */
    fun insert(
        id: String,
        type: String,
        created: Instant,
        payload: String,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO stripe_event (id, type, created, payload) VALUES (:id, :type, :created, CAST(:payload AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).param("id", id)
            .param("type", type)
            .param("created", OffsetDateTime.ofInstant(created, ZoneOffset.UTC))
            .param("payload", payload)
            .update() == 1

    fun find(id: String): StoredStripeEvent? =
        jdbc
            .sql(
                "SELECT id, type, created, payload::text AS payload, processed_at IS NOT NULL AS processed FROM stripe_event WHERE id = :id",
            ).param("id", id)
            .query { rs, _ ->
                StoredStripeEvent(
                    rs.getString("id"),
                    rs.getString("type"),
                    rs.getObject("created", OffsetDateTime::class.java).toInstant(),
                    rs.getString("payload"),
                    rs.getBoolean("processed"),
                )
            }.optional()
            .orElse(null)

    fun markProcessed(id: String) {
        jdbc.sql("UPDATE stripe_event SET processed_at = now() WHERE id = :id").param("id", id).update()
    }
}

internal data class BillingInvoiceRow(
    val id: UUID,
    val state: String,
    val number: String?,
    val issueDate: LocalDate?,
    val currency: String,
    val net: BigDecimal,
    val tax: BigDecimal,
    val gross: BigDecimal,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val paidAt: OffsetDateTime,
)

@Repository
internal class BillingInvoiceRepository(
    private val jdbc: JdbcClient,
) {
    /** In the issuer's scope: whether this Stripe invoice already has an ERIKA invoice. */
    fun existsForStripe(stripeInvoiceId: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM billing_invoice WHERE stripe_invoice_id = :s AND workspace_id = current_workspace_id())")
            .param("s", stripeInvoiceId)
            .query(Boolean::class.java)
            .single()

    fun insert(
        issuerWorkspaceId: UUID,
        customerWorkspaceId: UUID,
        stripeInvoiceId: String,
        erikaInvoiceId: UUID,
        issueDate: LocalDate,
        currency: String,
        net: BigDecimal,
        tax: BigDecimal,
        gross: BigDecimal,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
        paidAt: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO billing_invoice (id, workspace_id, customer_workspace_id, stripe_invoice_id, erika_invoice_id, issue_date,
                                             currency, net, tax, gross, period_start, period_end, paid_at)
                VALUES (:id, :ws, :customer, :stripe, :erika, :issue, :currency, :net, :tax, :gross, :start, :end, :paid)
                ON CONFLICT (stripe_invoice_id) DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("ws", issuerWorkspaceId)
            .param("customer", customerWorkspaceId)
            .param("stripe", stripeInvoiceId)
            .param("erika", erikaInvoiceId)
            .param("issue", issueDate)
            .param("currency", currency)
            .param("net", net)
            .param("tax", tax)
            .param("gross", gross)
            .param("start", periodStart)
            .param("end", periodEnd)
            .param("paid", OffsetDateTime.ofInstant(paidAt, ZoneOffset.UTC))
            .update()
    }

    /** In the issuer's scope (the issuing transaction): record the outcome of an ERIKA invoice. */
    fun markIssued(
        erikaInvoiceId: UUID,
        number: String,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE billing_invoice SET state = 'ISSUED', number = :number
                 WHERE workspace_id = current_workspace_id() AND erika_invoice_id = :id
                """.trimIndent(),
            ).param("number", number)
            .param("id", erikaInvoiceId)
            .update() == 1

    fun markRejected(erikaInvoiceId: UUID): Boolean =
        jdbc
            .sql("UPDATE billing_invoice SET state = 'REJECTED' WHERE workspace_id = current_workspace_id() AND erika_invoice_id = :id")
            .param("id", erikaInvoiceId)
            .update() == 1

    /** The current (customer) workspace's ERIKA invoices. */
    fun ofCurrentWorkspace(): List<BillingInvoiceRow> =
        jdbc
            .sql("SELECT * FROM billing_invoices_of_current_workspace()")
            .query { rs, _ ->
                BillingInvoiceRow(
                    id = rs.getObject("id", UUID::class.java),
                    state = rs.getString("state"),
                    number = rs.getString("number"),
                    issueDate = rs.getObject("issue_date", LocalDate::class.java),
                    currency = rs.getString("currency"),
                    net = rs.getBigDecimal("net"),
                    tax = rs.getBigDecimal("tax"),
                    gross = rs.getBigDecimal("gross"),
                    periodStart = rs.getObject("period_start", LocalDate::class.java),
                    periodEnd = rs.getObject("period_end", LocalDate::class.java),
                    paidAt = rs.getObject("paid_at", OffsetDateTime::class.java),
                )
            }.list()
}
