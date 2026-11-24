# 0033. Billing with Stripe

- Status: Accepted (pricing and the run against a Stripe test clock pending)
- Date: 2026-11-24

## Context
Stage 10 covers EX-03 (UC-08):
- a trial converts to a paid subscription;
- an unpaid workspace becomes read-only, but its archive stays readable, as the law requires;
- ERIKA's own invoices are valid e-invoices.

The build plan routes ERIKA's invoices through the creator. `billing` must call a published creator interface, not its tables (gap 3). The price is not decided yet (Requirements, open question).

## Decision

### Trial and subscription
- **ERIKA's own trial.** Every workspace gets a `billing_account` at sign-up, with `trial_ends_at` = now + 14 days, without a card. Existing workspaces got one by migration.
- **Subscribing.** The owner subscribes with **Stripe Checkout** (`POST /billing/checkout`, monthly or annual):
  - card or SEPA Direct Debit;
  - the rest of ERIKA's trial is passed as `subscription_data[trial_end]` (when more than 48 hours are left), so the first charge happens when the trial ends;
  - the Stripe customer is created once per workspace, with `metadata.workspace_id`.
- **Managing it.** Plan, payment method and cancellation are changed in the **Stripe customer portal** (`POST /billing/portal`).
- **Idempotency.** Session calls pass an `Idempotency-Key` to Stripe; the client's key is part of it.
- **VAT.** Prices are net. A configured Stripe tax rate (19 % exclusive) is applied unless the customer is an EU business outside Germany with a VAT ID; that is reverse charge, without tax.
- **Prices.** Price IDs and the tax rate come from configuration (`ERIKA_STRIPE_PRICE_MONTHLY` / `_ANNUAL`, `ERIKA_STRIPE_TAX_RATE`). The amounts live only in Stripe, and the billing page reads them from Stripe (cached for an hour). The open pricing decision therefore blocks no code.

### Webhooks
- **Endpoint.** `POST /webhooks/stripe` (api/webhooks.yaml) checks Stripe's signature: HMAC-SHA256 of `t.body` with the endpoint secret, at most 5 minutes old.
- **Stored once.** The event is stored in `stripe_event` (a system table; the event ID is the key, so a repeated delivery is stored once) and queued for the worker (`billing.process-stripe-event`).
- **Subscription events** (`customer.subscription.*`) update the account: status, price, interval, period end.
  - Older events never overwrite newer ones (`last_event_at`).
  - Every status change is audited (`billing.subscription.changed`).
- **`invoice.payment_failed`** is audited and emails the owners. Stripe's Smart Retries do the dunning.
- **API version.** It is pinned (`Stripe-Version`; the webhook endpoint uses the same one). The parser reads both the pre-2025 and the current field locations: the period end on the subscription or on its item, net amounts from `total_excluding_tax`.

### What a workspace may do (`Entitlements`)

| State | When | Effect |
|---|---|---|
| DISABLED | billing switched off (`ERIKA_BILLING_ENABLED=false`, local and closed beta) | everything allowed |
| EXEMPT | ERIKA's issuer workspace or `exempt` flag | everything allowed |
| ACTIVE | subscription `active` or `trialing` | everything allowed |
| PAST_DUE | Stripe is retrying a failed payment | everything allowed; banner and email |
| READ_ONLY | subscription `unpaid` or `paused` (dunning exhausted), or no running subscription after the trial | read, download, export, pay; no changes |
| TRIAL | otherwise, until `trial_ends_at` | everything allowed |

`billing.web.EntitlementInterceptor` answers state-changing requests of a READ_ONLY workspace with 403 `SUBSCRIPTION_REQUIRED`. Exports, feedback, `/auth` and `/billing` stay open, so the archive remains accessible and the customer can pay.

### ERIKA's own e-invoices
- **When.** For each `invoice.paid` with an amount, the worker builds a `creator.SystemInvoiceRequest` (`OwnInvoices`).
  - Buyer: the customer's workspace profile, with Stripe's billing address as fallback, and the billing email from Stripe.
  - Lines: one per Stripe line, with its service period.
  - VAT: 19 % domestic, or reverse charge.
  - Payment: the invoice is dated on the payment day and notes that the amount is already paid.
  - Anything else (another tax rate, no VAT outside reverse charge) fails the job and logs an error, which alerts the operator.
- **Issued through the creator.** It goes to the published **`creator.SystemInvoices`** in the issuer workspace (`ERIKA_BILLING_ISSUER_WORKSPACE`, ERIKA's company data and number pattern). Issuing then works as for every invoice (ADR 0031):
  - checks, gap-free number and file in the worker;
  - **validation, which must not be ERROR**: this is how ERIKA's invoices pass VW-03;
  - write-once archive.
- **Sent.** `invoice_draft.send_to` makes the issuing transaction queue the email: to the billing address and to the customer's **intake address**, so the invoice also lands in their ERIKA inbox like any received e-invoice. Stripe's own invoice emails are switched off.
- **One per Stripe invoice.** `billing_invoice` (rows in the issuer workspace) maps Stripe invoice → ERIKA invoice; the unique Stripe ID and the creator's idempotency key prevent duplicates. Customers read theirs through a SECURITY DEFINER function limited to the current workspace.
- **Outcome.** The row learns its number from the creator's `InvoiceIssued` event in the issuing transaction, or REJECTED from `InvoiceIssueRejected`.

### Account closure
`identity.WorkspaceClosed` (ADR 0032) queues `billing.cancel-subscription`. Stripe cancels at once; an unknown or already cancelled subscription counts as done.

## Consequences
- **Testing.** Automated tests run against a fake Stripe API and signed webhook payloads (`BillingIT`). The exit criterion against Stripe itself (test clock: trial ends → payment collected → e-invoice issued; failed SEPA payment → read-only) is a manual run on staging (docs/ops/billing.md).
- **Billing is off by default.** Before it is switched on in production, the trial dates of the beta users are set, Stripe is configured, and the issuer workspace is set up (runbook).
- **Stripe is a new sub-processor** for payment data (card and bank data stay at Stripe). The privacy notice, the sub-processor list and the record of processing activities must name it before billing goes live (docs/legal/README.md).
- **Other tax cases.** Supporting other VAT cases for ERIKA's invoices (customers outside the EU) means extending `OwnInvoices`; until then the operator issues those by hand.
