package de.erika.billing

import de.erika.creator.BuyerSnapshot
import de.erika.creator.LineVatRate
import de.erika.creator.SystemInvoiceLine
import de.erika.creator.SystemInvoiceRequest
import de.erika.creator.SystemInvoices
import de.erika.creator.VatTreatment
import de.erika.identity.WorkspaceContacts
import de.erika.identity.WorkspaceProfile
import de.erika.identity.WorkspaceProfiles
import de.erika.intake.IntakeMailboxes
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import de.erika.platform.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A paid Stripe invoice that ERIKA can't turn into an e-invoice by itself (the operator issues it). */
class UnsupportedBillingInvoice(
    message: String,
) : RuntimeException(message)

/** Receiving side of the webhook: stores the event once and queues it for the worker. */
@Component
internal class StripeEventInbox(
    private val events: StripeEventRepository,
    private val jobs: JobQueue,
) {
    /** Returns false for a repeated delivery (already stored). */
    @Transactional
    fun receive(
        id: String,
        type: String,
        created: Instant,
        payload: String,
    ): Boolean {
        if (!events.insert(id, type, created, payload)) return false
        jobs.enqueue(
            ProcessStripeEventJobHandler.TYPE,
            ProcessStripeEventJobHandler.Payload(id),
            maxAttempts = ProcessStripeEventJobHandler.MAX_ATTEMPTS,
        )
        return true
    }
}

/**
 * Applies one Stripe event (worker). Idempotent: a processed event is skipped, older
 * subscription events don't overwrite newer ones, and each paid Stripe invoice gets exactly one
 * ERIKA invoice (unique Stripe invoice ID plus the creator's idempotency key).
 */
@Component
internal class ProcessStripeEventJobHandler(
    private val stored: StripeEventRepository,
    private val accounts: BillingAccountRepository,
    private val invoices: BillingInvoiceRepository,
    private val systemInvoices: SystemInvoices,
    private val profiles: WorkspaceProfiles,
    private val contacts: WorkspaceContacts,
    private val mailboxes: IntakeMailboxes,
    private val tenant: TenantContext,
    private val outbox: Outbox,
    private val messages: MessageSource,
    private val mail: MailProperties,
    private val props: BillingProperties,
    private val json: JsonMapper,
    private val events: ApplicationEventPublisher,
    private val addons: MaraAddons,
    private val addonRepo: AddonRepository,
    private val jobs: JobQueue,
) : JobHandler<ProcessStripeEventJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        val eventId: String,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val event = stored.find(payload.eventId) ?: return
        if (event.processed) return
        val obj = json.readTree(event.payload).path("data").path("object")
        when (event.type) {
            "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> {
                subscription(event, obj)
            }

            "invoice.paid" -> {
                paid(event, obj)
            }

            "invoice.payment_failed" -> {
                failed(obj)
            }

            else -> {
                log.debug("Stripe event {} of type {} ignored", event.id, event.type)
            }
        }
        stored.markProcessed(event.id)
    }

    private fun workspaceOf(obj: JsonNode): UUID? {
        val customer = obj.path("customer").asString()
        val byCustomer = customer.takeIf { it.isNotEmpty() }?.let(accounts::workspaceForCustomer)
        val byMetadata =
            obj
                .path("metadata")
                .path("workspace_id")
                .asString()
                .let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (byCustomer != null && byMetadata != null && byCustomer != byMetadata) {
            log.error(
                "Stripe object {} names workspace {} but its customer belongs to {}; ignored",
                obj.path("id").asString(),
                byMetadata,
                byCustomer,
            )
            return null
        }
        return byCustomer ?: byMetadata
    }

    private fun subscription(
        event: StoredStripeEvent,
        sub: JsonNode,
    ) {
        val workspaceId = workspaceOf(sub) ?: return log.warn("Subscription event {} for an unknown customer ignored", event.id)
        tenant.enterWorkspace(workspaceId)
        val account = accounts.find(workspaceId) ?: return
        if (account.lastEventAt != null && event.created.isBefore(account.lastEventAt.toInstant())) return
        val items =
            sub
                .path("items")
                .path("data")
                .values()
                .toList()
        val priceOf = { i: JsonNode -> i.path("price").path("id").asString() }
        // The plan's item (not an add-on's); older subscriptions have only that one.
        val item =
            items.firstOrNull { priceOf(it) in setOf(props.priceMonthly, props.priceAnnual) }
                ?: items.firstOrNull { !addons.isMaraPrice(priceOf(it)) && !addons.isSeatPrice(priceOf(it)) }
                ?: sub.path("items").path("data").path(0)
        val status = if (event.type == "customer.subscription.deleted") "canceled" else sub.path("status").asString()
        val snapshot =
            SubscriptionSnapshot(
                customerId = sub.path("customer").asString(),
                subscriptionId = sub.path("id").asString(),
                status = status,
                priceId =
                    item
                        .path("price")
                        .path("id")
                        .asString()
                        .ifEmpty { null },
                interval =
                    item
                        .path("price")
                        .path("recurring")
                        .path("interval")
                        .asString()
                        .ifEmpty { null },
                // Since API version 2025-03-31 the period is on the item; before, on the subscription.
                currentPeriodEnd = epoch(item.path("current_period_end")) ?: epoch(sub.path("current_period_end")),
                cancelAtPeriodEnd = sub.path("cancel_at_period_end").asBoolean(),
                eventCreated = event.created,
            )
        accounts.applySubscription(workspaceId, snapshot)
        maraItems(
            workspaceId,
            event,
            status,
            items.firstOrNull {
                addons.isMaraPrice(priceOf(it))
            },
            items.firstOrNull { addons.isSeatPrice(priceOf(it)) },
        )
        if (account.subscriptionStatus != status || account.subscriptionId != snapshot.subscriptionId) {
            events.publishEvent(SubscriptionChanged(workspaceId, snapshot.subscriptionId, account.subscriptionStatus, status, event.id))
        }
    }

    /** The MARA add-on as the subscription now carries it (ADR 0043). */
    private fun maraItems(
        workspaceId: UUID,
        event: StoredStripeEvent,
        status: String,
        mara: JsonNode?,
        seats: JsonNode?,
    ) {
        val row = addonRepo.find(workspaceId)
        if (row == null && mara == null) return
        val active = mara != null && status != "canceled"
        val seatsBilled = if (active) seats?.path("quantity")?.asInt() ?: 0 else 0
        val pending = row?.pending?.takeUnless { (it == "BOOKING" && active) || (it == "CANCELLING" && !active) }
        addonRepo.setItems(
            workspaceId,
            mara
                ?.path("id")
                ?.asString()
                ?.ifEmpty { null }
                .takeIf { active },
            seats
                ?.path("id")
                ?.asString()
                ?.ifEmpty { null }
                .takeIf { active },
            seatsBilled,
            active,
            pending,
        )
        if (row?.active == true && !active) addonRepo.endTrial(workspaceId)
        if (row?.active != active || row.seatsBilled != seatsBilled) {
            events.publishEvent(MaraAddonChanged(workspaceId, active, seatsBilled, event.id))
        }
        // Booked in the checkout: the seats above the included ones follow from the worker.
        if (active && row?.itemId == null) jobs.enqueue(MaraSeatsJobHandler.TYPE, MaraSeatsJobHandler.Payload(), workspaceId)
    }

    private fun failed(inv: JsonNode) {
        val workspaceId = workspaceOf(inv) ?: return
        tenant.enterWorkspace(workspaceId)
        events.publishEvent(PaymentFailed(workspaceId, inv.path("id").asString(), inv.path("attempt_count").asInt()))
        contacts.owners().forEach { owner ->
            val locale = Locale.forLanguageTag(owner.locale)
            outbox.send(
                Mail(
                    to = owner.email,
                    subject = messages.getMessage("mail.billing.failed.subject", null, locale),
                    body =
                        messages.getMessage(
                            "mail.billing.failed.body",
                            arrayOf(owner.displayName, "${mail.webBaseUrl}/settings/billing"),
                            locale,
                        ),
                ),
            )
        }
    }

    /** A paid invoice: audit it for the customer and issue ERIKA's own e-invoice from the issuer workspace. */
    private fun paid(
        event: StoredStripeEvent,
        inv: JsonNode,
    ) {
        val amountPaid = inv.path("amount_paid").asLong()
        if (amountPaid <= 0) return // e.g. the 0 € invoice when a Stripe trial starts
        val customerWorkspace = workspaceOf(inv) ?: return log.warn("Paid invoice event {} for an unknown customer ignored", event.id)
        val stripeInvoiceId = inv.path("id").asString()
        val currency = inv.path("currency").asString().uppercase()

        tenant.enterWorkspace(customerWorkspace)
        events.publishEvent(PaymentSucceeded(customerWorkspace, stripeInvoiceId, cents(amountPaid).toPlainString(), currency))
        val profile = profiles.of(customerWorkspace)
        val inbox = if (props.copyToInbox) mailboxes.addressOf(customerWorkspace) else null

        val issuer = checkNotNull(props.issuerWorkspaceId) { "erika.billing.issuer-workspace-id is not set" }
        tenant.enterWorkspace(issuer)
        if (invoices.existsForStripe(stripeInvoiceId)) return
        val request = OwnInvoices.request(inv, profile, listOfNotNull(inv.path("customer_email").asString().ifEmpty { null }, inbox))
        val erikaInvoiceId = systemInvoices.issue(issuer, request)
        val totals = OwnInvoices.totals(inv)
        invoices.insert(
            issuerWorkspaceId = issuer,
            customerWorkspaceId = customerWorkspace,
            stripeInvoiceId = stripeInvoiceId,
            erikaInvoiceId = erikaInvoiceId,
            issueDate = request.issueDate,
            currency = currency,
            net = totals.first,
            tax = totals.second,
            gross = totals.first + totals.second,
            periodStart = request.periodStart,
            periodEnd = request.periodEnd,
            paidAt = OwnInvoices.paidAt(inv) ?: event.created,
        )
        events.publishEvent(OwnInvoiceRequested(issuer, customerWorkspace, stripeInvoiceId, erikaInvoiceId))
    }

    companion object {
        const val TYPE = "billing.process-stripe-event"
        const val MAX_ATTEMPTS = 8

        fun epoch(node: JsonNode): Instant? = node.takeIf { it.isNumber }?.let { Instant.ofEpochSecond(it.asLong()) }

        fun cents(amount: Long): BigDecimal = BigDecimal.valueOf(amount, 2)
    }
}

/**
 * Turns a paid Stripe invoice into the request for ERIKA's own e-invoice (ADR 0033): the buyer
 * from the customer's workspace profile (Stripe's address as fallback), one line per Stripe line
 * with its service period, German VAT (19 %) or reverse charge for EU businesses outside Germany,
 * and a note that it is already paid.
 */
object OwnInvoices {
    private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    private val GERMAN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val EU =
        setOf(
            "AT",
            "BE",
            "BG",
            "CY",
            "CZ",
            "DE",
            "DK",
            "EE",
            "ES",
            "FI",
            "FR",
            "GR",
            "HR",
            "HU",
            "IE",
            "IT",
            "LT",
            "LU",
            "LV",
            "MT",
            "NL",
            "PL",
            "PT",
            "RO",
            "SE",
            "SI",
            "SK",
        )

    /** Net and VAT of the Stripe invoice. */
    fun totals(inv: JsonNode): Pair<BigDecimal, BigDecimal> {
        val total = inv.path("total").asLong()
        val net = inv.path("total_excluding_tax").takeIf { it.isNumber }?.asLong() ?: inv.path("subtotal").asLong()
        return ProcessStripeEventJobHandler.cents(net) to ProcessStripeEventJobHandler.cents(total - net)
    }

    fun paidAt(inv: JsonNode): Instant? = ProcessStripeEventJobHandler.epoch(inv.path("status_transitions").path("paid_at"))

    fun request(
        inv: JsonNode,
        profile: WorkspaceProfile,
        sendTo: List<String>,
    ): SystemInvoiceRequest {
        val (net, tax) = totals(inv)
        val buyer = buyer(inv, profile)
        val treatment =
            when {
                tax.signum() > 0 -> {
                    val expected = net.multiply(BigDecimal("0.19")).setScale(2, RoundingMode.HALF_UP)
                    if (expected.subtract(tax).abs() > BigDecimal("0.01")) {
                        throw UnsupportedBillingInvoice("Stripe invoice ${inv.path("id").asString()}: VAT is not 19 %")
                    }
                    VatTreatment.DOMESTIC
                }

                buyer.countryCode != "DE" && buyer.countryCode in EU && !buyer.vatId.isNullOrBlank() -> {
                    VatTreatment.REVERSE_CHARGE
                }

                else -> {
                    throw UnsupportedBillingInvoice(
                        "Stripe invoice ${inv.path("id").asString()}: no VAT, but not an EU reverse-charge customer",
                    )
                }
            }
        val paidOn = (paidAt(inv) ?: Instant.now()).atZone(BERLIN).toLocalDate()
        val stripeLines =
            inv
                .path("lines")
                .path("data")
                .values()
                .toList()
        val lines = lines(stripeLines, net)
        val periodStart = stripeLines.mapNotNull { day(it.path("period").path("start")) }.minOrNull()
        val periodEnd = stripeLines.mapNotNull { day(it.path("period").path("end"))?.minusDays(1) }.maxOrNull()
        val validPeriod = periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart)
        val stripeNumber = inv.path("number").asString().ifEmpty { inv.path("id").asString() }
        return SystemInvoiceRequest(
            reference = "stripe:${inv.path("id").asString()}",
            buyer = buyer,
            vatTreatment = treatment,
            lines = lines,
            issueDate = paidOn,
            dueDate = paidOn,
            periodStart = periodStart.takeIf { validPeriod },
            periodEnd = periodEnd.takeIf { validPeriod },
            paymentTerms = "Bereits bezahlt am ${paidOn.format(GERMAN)}.",
            note = "Der Rechnungsbetrag wurde am ${paidOn.format(
                GERMAN,
            )} über Stripe eingezogen (Beleg $stripeNumber). Bitte nicht erneut überweisen.",
            sendTo = sendTo.distinct(),
        )
    }

    /** One line per Stripe line when they add up to the net total; otherwise one line for the whole amount. */
    private fun lines(
        stripeLines: List<JsonNode>,
        net: BigDecimal,
    ): List<SystemInvoiceLine> {
        val mapped =
            stripeLines.map { line ->
                val amount =
                    ProcessStripeEventJobHandler.cents(
                        line.path("amount_excluding_tax").takeIf { it.isNumber }?.asLong() ?: line.path("amount").asLong(),
                    )
                val quantity = line.path("quantity").takeIf { it.isNumber && it.asLong() > 0 }?.asLong() ?: 1L
                val unit = amount.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP)
                val exact = unit.multiply(BigDecimal.valueOf(quantity)).compareTo(amount) == 0
                SystemInvoiceLine(
                    name =
                        line
                            .path("description")
                            .asString()
                            .ifEmpty { "ERIKA-Abonnement" }
                            .take(200),
                    quantity = if (exact) BigDecimal.valueOf(quantity) else BigDecimal.ONE,
                    netPrice = if (exact) unit.stripTrailingZeros().let { if (it.scale() < 2) it.setScale(2) else it } else amount,
                )
            }
        val sum = mapped.fold(BigDecimal.ZERO) { acc, l -> acc + l.quantity.multiply(l.netPrice) }
        return if (mapped.isNotEmpty() && sum.compareTo(net) == 0) {
            mapped
        } else {
            listOf(SystemInvoiceLine(name = "ERIKA-Abonnement", quantity = BigDecimal.ONE, netPrice = net))
        }.map { it.copy(vatRate = LineVatRate.STANDARD_19) }
    }

    private fun buyer(
        inv: JsonNode,
        p: WorkspaceProfile,
    ): BuyerSnapshot {
        val complete = !p.street.isNullOrBlank() && !p.postalCode.isNullOrBlank() && !p.city.isNullOrBlank()
        val address = inv.path("customer_address")
        return BuyerSnapshot(
            name = p.name,
            street = if (complete) p.street else address.path("line1").asString().ifEmpty { null },
            additionalLine = if (complete) null else address.path("line2").asString().ifEmpty { null },
            postalCode = if (complete) p.postalCode else address.path("postal_code").asString().ifEmpty { null },
            city = if (complete) p.city else address.path("city").asString().ifEmpty { null },
            countryCode = if (complete) p.countryCode else address.path("country").asString().ifEmpty { p.countryCode },
            vatId = p.vatId,
            email = inv.path("customer_email").asString().ifEmpty { null },
        )
    }

    private fun day(node: JsonNode): LocalDate? = ProcessStripeEventJobHandler.epoch(node)?.atZone(BERLIN)?.toLocalDate()
}
