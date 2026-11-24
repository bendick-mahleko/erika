package de.erika.billing

import de.erika.creator.InvoiceIssueRejected
import de.erika.creator.InvoiceIssued
import de.erika.identity.WorkspaceClosed
import de.erika.identity.WorkspaceContacts
import de.erika.identity.WorkspaceCreated
import de.erika.identity.WorkspaceProfiles
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.MailProperties
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A plan as the billing page shows it. */
internal data class Offer(
    val plan: BillingPlan,
    val available: Boolean,
    val netAmount: BigDecimal?,
    val currency: String?,
)

/**
 * Trial, subscription and the Stripe sessions (EX-03, ADR 0033). Runs in the caller's
 * transaction, scoped to the workspace.
 */
@Service
internal class Billing(
    private val props: BillingProperties,
    private val accounts: BillingAccountRepository,
    private val stripe: StripeClient,
    private val profiles: WorkspaceProfiles,
    private val contacts: WorkspaceContacts,
    private val mail: MailProperties,
    private val jobs: JobQueue,
    private val invoices: BillingInvoiceRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun state(workspaceId: UUID): BillingState = Entitlements.state(accounts.find(workspaceId), props)

    @Transactional(propagation = Propagation.MANDATORY)
    fun account(workspaceId: UUID): BillingAccount? = accounts.find(workspaceId)

    fun offers(): List<Offer> =
        BillingPlan.entries.map { plan ->
            val priceId = priceOf(plan)
            val price = priceId?.takeIf { props.enabled && props.stripeSecretKey.isNotBlank() }?.let(stripe::price)
            Offer(
                plan = plan,
                available = priceId != null,
                netAmount = price?.path("unit_amount")?.takeIf { it.isNumber }?.let { BigDecimal.valueOf(it.asLong(), 2) },
                currency =
                    price
                        ?.path("currency")
                        ?.asString()
                        ?.uppercase()
                        ?.takeIf { it.isNotEmpty() },
            )
        }

    fun planOf(priceId: String?): BillingPlan? =
        when (priceId) {
            null, "" -> null
            props.priceMonthly -> BillingPlan.MONTHLY
            props.priceAnnual -> BillingPlan.ANNUAL
            else -> null
        }

    /** The URL of a Stripe Checkout page for [plan]; keeps the rest of the trial. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun checkout(
        workspaceId: UUID,
        actorId: UUID,
        plan: BillingPlan,
        idempotencyKey: String,
    ): String {
        requireEnabled()
        val priceId = priceOf(plan) ?: throw ErikaException("PLAN_NOT_AVAILABLE", HttpStatus.CONFLICT, "This plan is not offered.")
        val account =
            accounts.find(workspaceId) ?: throw ErikaException("BILLING_ACCOUNT_MISSING", HttpStatus.CONFLICT, "No billing account.")
        if (Entitlements.running(account.subscriptionStatus)) {
            throw ErikaException(
                "ALREADY_SUBSCRIBED",
                HttpStatus.CONFLICT,
                "There is already a subscription. Change it in the customer portal.",
            )
        }
        val profile = profiles.of(workspaceId)
        val customer = account.stripeCustomerId ?: createCustomer(workspaceId, actorId)
        // German VAT unless it is a reverse-charge customer (EU business outside Germany with a VAT ID).
        val reverseCharge = profile.countryCode != "DE" && !profile.vatId.isNullOrBlank()
        val trialEnd = account.trialEndsAt.toInstant().takeIf { it.isAfter(Instant.now().plus(MIN_TRIAL_END)) }
        val params =
            linkedMapOf(
                "mode" to "subscription",
                "customer" to customer,
                "line_items[0][price]" to priceId,
                "line_items[0][quantity]" to "1",
                "payment_method_types[0]" to "card",
                "payment_method_types[1]" to "sepa_debit",
                "locale" to "de",
                "client_reference_id" to workspaceId.toString(),
                "success_url" to "${mail.webBaseUrl}/settings/billing?checkout=success",
                "cancel_url" to "${mail.webBaseUrl}/settings/billing?checkout=cancelled",
                "subscription_data[metadata][workspace_id]" to workspaceId.toString(),
                "subscription_data[trial_end]" to trialEnd?.epochSecond?.toString(),
                "subscription_data[default_tax_rates][0]" to props.taxRateId.takeIf { it.isNotBlank() && !reverseCharge },
            )
        val session = stripe.createCheckoutSession(params, "checkout-$workspaceId-$idempotencyKey")
        events.publishEvent(CheckoutStarted(workspaceId, actorId, plan.name))
        return session.path("url").asString().ifEmpty { throw StripeException(502, null, "Checkout session without URL") }
    }

    /** The URL of the Stripe customer portal (payment method, plan, cancellation). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun portal(
        workspaceId: UUID,
        idempotencyKey: String,
    ): String {
        requireEnabled()
        val customer =
            accounts.find(workspaceId)?.stripeCustomerId
                ?: throw ErikaException("NO_SUBSCRIPTION", HttpStatus.CONFLICT, "Start a subscription first.")
        val session =
            stripe.createPortalSession(
                mapOf("customer" to customer, "return_url" to "${mail.webBaseUrl}/settings/billing", "locale" to "de"),
                "portal-$workspaceId-$idempotencyKey",
            )
        return session.path("url").asString()
    }

    private fun createCustomer(
        workspaceId: UUID,
        actorId: UUID,
    ): String {
        val profile = profiles.of(workspaceId)
        val owner = contacts.member(actorId)
        val customer =
            stripe.createCustomer(
                mapOf(
                    "name" to profile.name,
                    "email" to owner?.email,
                    "address[line1]" to profile.street,
                    "address[postal_code]" to profile.postalCode,
                    "address[city]" to profile.city,
                    "address[country]" to profile.countryCode,
                    "preferred_locales[0]" to "de",
                    "metadata[workspace_id]" to workspaceId.toString(),
                ),
                "customer-$workspaceId",
            )
        val id = customer.path("id").asString().ifEmpty { throw StripeException(502, null, "Customer without ID") }
        accounts.setCustomer(workspaceId, id)
        return id
    }

    private fun requireEnabled() {
        if (!props.enabled) throw ErikaException("BILLING_DISABLED", HttpStatus.CONFLICT, "Billing is not active.")
    }

    private fun priceOf(plan: BillingPlan) =
        when (plan) {
            BillingPlan.MONTHLY -> props.priceMonthly
            BillingPlan.ANNUAL -> props.priceAnnual
        }.takeIf { it.isNotBlank() }

    // --- events from other modules ------------------------------------------------------

    /** Every new workspace starts its trial in the sign-up transaction. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: WorkspaceCreated) {
        val ends = Instant.now().plus(Duration.ofDays(props.trialDays))
        if (accounts.startTrial(event.workspaceId, ends)) events.publishEvent(TrialStarted(event.workspaceId, event.actorId, ends))
    }

    /** A closed account's subscription is cancelled (EX-04); the Stripe call runs in the worker. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: WorkspaceClosed) {
        val account = accounts.find(event.workspaceId) ?: return
        val subscription = account.subscriptionId ?: return
        if (account.subscriptionStatus in setOf("canceled", "incomplete_expired")) return
        jobs.enqueue(CancelSubscriptionJobHandler.TYPE, CancelSubscriptionJobHandler.Payload(subscription), event.workspaceId)
        events.publishEvent(SubscriptionCancelRequested(event.workspaceId, subscription, "workspace closed"))
    }

    /** In the issuing transaction (issuer workspace): ERIKA's own invoice got its number. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: InvoiceIssued) {
        if (event.workspaceId == props.issuerWorkspaceId) invoices.markIssued(event.invoiceId, event.number)
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: InvoiceIssueRejected) {
        if (event.workspaceId == props.issuerWorkspaceId) invoices.markRejected(event.invoiceId)
    }

    private companion object {
        /** Stripe needs a trial end at least 48 hours ahead; shorter remainders are dropped. */
        val MIN_TRIAL_END: Duration = Duration.ofHours(49)
    }
}

/** Cancels a Stripe subscription (account closed). Idempotent: an unknown or cancelled subscription is fine. */
@Component
internal class CancelSubscriptionJobHandler(
    private val stripe: StripeClient,
) : JobHandler<CancelSubscriptionJobHandler.Payload> {
    data class Payload(
        val subscriptionId: String,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) = stripe.cancelSubscription(payload.subscriptionId)

    companion object {
        const val TYPE = "billing.cancel-subscription"
    }
}
