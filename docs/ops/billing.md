# Billing with Stripe: setup, go-live and tests

Design: [ADR 0033](../adr/0033-billing-with-stripe.md). Billing is **off** until go-live (`ERIKA_BILLING_ENABLED=false`): no trial limits, no Stripe calls.

## 1. Decide the pricing (open question)

Monthly and annual net prices, trial length (default 14 days, `ERIKA_BILLING_TRIAL_DAYS`). Prices exist only in Stripe; ERIKA shows what Stripe returns.

## 2. Stripe account (test mode first, then live)

1. **Products and prices:** one product "ERIKA", two recurring prices, monthly and annual, tax behaviour *exclusive*. Note the `price_…` IDs → `erika_stripe_price_monthly` / `_annual` (group_vars) or `ERIKA_STRIPE_PRICE_*`.
   - **MARA add-on (Stage 10, ADR 0043):** a product "MARA" with two prices (monthly €39, annual), and a product "MARA Nutzer" with two per-unit prices for each further user (price still to decide). All in the same intervals as the ERIKA prices. → `ERIKA_STRIPE_PRICE_MARA_MONTHLY`, `_MARA_ANNUAL`, `_MARA_SEAT_MONTHLY`, `_MARA_SEAT_ANNUAL` (group_vars `erika_stripe_price_mara_*`). Empty = the add-on isn't sold and MARA is free where `ERIKA_FEATURE_MARA` is on.
2. **Tax rate:** "Umsatzsteuer 19 %", exclusive, country DE → `txr_…` into `ERIKA_STRIPE_TAX_RATE`.
3. **Payment methods:** cards and SEPA Direct Debit on.
4. **Dunning (Billing → Subscriptions and emails):**
   - Smart Retries, e.g. 4 attempts over 2 weeks;
   - after the last attempt, **mark the subscription as unpaid**. ERIKA makes the workspace read-only then.
5. **Emails:** switch **off** "Send finalized invoices and credit notes" and "Email customers about successful payments", because ERIKA sends its own e-invoices. Keep failed-payment and SEPA mandate emails on.
6. **Customer portal:** allow updating payment methods, switching between the two prices, and cancelling at period end. Set the business information and the link to the terms.
7. **Webhook endpoint:** `https://<domain>/webhooks/stripe`.
   - API version: the one in `erika.billing.stripe-api-version` (default `2025-09-30.clover`).
   - Events: `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, `invoice.payment_failed`.
   - The signing secret → `erika_stripe_webhook_secret` (SOPS).
8. **API key:** a restricted key is enough. It needs write access to Customers, Checkout Sessions and Customer portal sessions, write access to Subscriptions and Subscription items (cancel on closure; the MARA add-on and its seats), and read access to Prices.
   - In the customer portal, don't allow changing the MARA quantities: ERIKA sets the seats itself. → `erika_stripe_secret_key` (SOPS).

## 3. ERIKA's issuer workspace

ERIKA's own invoices are issued in a normal ERIKA workspace:
1. Sign up "ERIKA <company>" in production (team account, 2FA).
2. Fill in the company data and invoice settings (VAT ID, IBAN, contact). Use a number pattern of its own, e.g. `ERIKA-{YYYY}-{NNNNN}`.
3. Put its workspace ID into `erika_billing_issuer_workspace`. It is never limited by billing (EXEMPT).

## 4. Go-live

1. Legal: Stripe in the privacy notice, the sub-processor list and the record of processing activities ([docs/legal/README.md](../legal/README.md)).
2. Set the trial end of the beta users, e.g. 30 days after go-live:
   ```sql
   UPDATE erika.billing_account SET trial_ends_at = '<date>' WHERE subscription_id IS NULL;
   ```
   Exempt workspaces (team, partners): `UPDATE erika.billing_account SET exempt = true WHERE workspace_id = '<id>';`
3. Set `erika_billing_enabled: true` and the IDs, deploy, and check `GET /api/v1/billing` → `state: TRIAL` with prices.

## 5. Exit criteria with a Stripe test clock (staging, test mode)

Needs the Stripe CLI and a staging workspace whose Stripe customer is attached to a test clock. Create the customer in the dashboard with a clock, then set it on the account:
`UPDATE erika.billing_account SET stripe_customer_id = 'cus_…' WHERE workspace_id = '<id>';`

**A. Trial → payment → e-invoice (EX-03)**
1. In ERIKA: Abrechnung → "Monatlich abonnieren", pay with test card 4242 4242 4242 4242 (or SEPA test IBAN DE89370400440532013000).
2. Check: `GET /billing` → ACTIVE (Stripe trial = rest of the ERIKA trial).
3. Advance the test clock past the trial end. Stripe charges and sends `invoice.paid`.
4. Check:
   - "Ihre Rechnungen von ERIKA" lists an issued invoice;
   - in the issuer workspace the invoice has a valid validation result (VW-03);
   - the email arrived;
   - the invoice is in the customer's inbox;
   - the audit log has `billing.payment.succeeded` (customer) and `billing.own_invoice.requested` (issuer).

**B. Failed SEPA payment → dunning → read-only**
1. Second workspace, subscribe with the SEPA test IBAN that fails (`DE62370400440532013001`).
2. Advance the clock through the trial end and the retries. Expected: `invoice.payment_failed` (owner email, banner "Zahlung offen", PAST_DUE), then the subscription becomes `unpaid` and the workspace READ_ONLY. Changes now answer `SUBSCRIPTION_REQUIRED`; reading, export and paying still work.
3. Check the audit log: `billing.payment.failed`, `billing.subscription.changed` (active → past_due → unpaid).

**C. MARA add-on: trial, seats added and removed mid-month (MARA Stage 10 exit criterion)**
1. Third workspace (ERIKA_FEATURE_MARA on, MARA prices set): Abrechnung → MARA → confirm the works-council notice → "Probezeit starten". Check `GET /billing/addons/mara` → TRIAL, ends in 30 days; creating a work order works.
2. Subscribe to ERIKA monthly with the test card; then "MARA buchen". Expected: a `subscription_item` for the MARA price (proration on), `customer.subscription.updated` with the item → state ACTIVE, audit `billing.mara.book`, `billing.mara.changed`.
3. Invite 3 technicians (4 users): a seat item with quantity 1 appears (proration line on the upcoming invoice). Advance the clock by 10 days, invite one more (quantity 2), advance 5 days, remove a technician (quantity 1).
4. Advance past the period end. Check the Stripe invoice: MARA €39, seats prorated per day for 1 and 2 seats as above; ERIKA's own e-invoice shows the same lines.
5. "MARA kündigen": both items removed with a credit for the rest of the period; state READ_ONLY; the app shows "nicht gebucht" and keeps its outbox; reading and export work.
6. A fourth workspace: let the MARA trial run out (set `trial_ends_at` to the past on staging) → READ_ONLY without Stripe.

Record the dates and results in the release checklist.

## Operations

- **ERIKA invoice REJECTED** (the error log says "System invoice … was rejected"): open the draft in the issuer workspace, fix the data (usually the customer's address), and issue it there by hand. The billing row keeps REJECTED; note the number in the support tracker.
- **Webhook backlog:** `SELECT type, count(*) FROM erika.stripe_event WHERE processed_at IS NULL GROUP BY type;` Failed jobs: `job.type = 'billing.process-stripe-event' AND state = 'FAILED'`. Fix the cause, then requeue: `UPDATE erika.job SET state = 'QUEUED', attempts = 0, run_at = now() WHERE id = '<id>';`
- **Replaying an event:** Stripe dashboard → Webhooks → event → Resend. ERIKA ignores duplicates by ID.
