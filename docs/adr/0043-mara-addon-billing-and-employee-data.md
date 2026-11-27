# 0043. MARA: add-on billing, entitlement and employee data

- Status: Accepted (MARA Stage 10); the seat price, the legal texts and the colleague-visibility question (lawyer, question 12) are open
- Date: 2026-11-27
- Extends: ADR 0033 (billing), amends ADR 0039 (visibility of times)

## Context
MARA must be bookable on the ERIKA subscription: €39 a month with 3 users, further users charged as seats, a 30-day trial (M-AC-02). After the trial or a cancellation, data must stay readable and exportable while nothing new is saved or synced. Employees' time data needs clear limits (M-NF-09), and the owner must see the works-council notice before MARA starts (M-BR-09). Until Stage 10, MARA was switched on per installation only (`erika.features.mara`).

## Decision
- **Two switches.** `erika.features.mara` stays the switch per installation (404 when off). Where it is on, the add-on decides per workspace (`billing.MaraEntitlement`).
- **States** (`billing_addon`, one row per workspace and add-on):
  - DISABLED: billing off, or no MARA prices configured. MARA is free.
  - NONE: never started.
  - TRIAL: 30 days, once per workspace, started by an owner who confirms the works-council notice. The confirmation is stored with date and person and audited.
  - ACTIVE: the add-on item is on a running subscription (active, trialing, past_due), as Stripe's webhook reports it.
  - EXEMPT: beta firms (`exempt`), or an exempt ERIKA workspace.
  - READ_ONLY: trial over, or cancelled. ERIKA's own READ_ONLY also makes MARA read-only.
- **Enforcement.** Office changes on MARA paths and phone pushes need a writable state: otherwise 403 `MARA_NOT_BOOKED` or `MARA_READ_ONLY` (`MaraFeatureGuard`). Always allowed: reading, exports, signing in, device revocation (security), finishing photo uploads of records already accepted, and booking. A refused push applies nothing; the app keeps its outbox, still pulls, and shows that MARA isn't booked (`SyncOutcome.readOnly`).
- **Stripe items.** The add-on is a subscription item with the MARA price (monthly or annual, matching the plan). Users above the included 3 are a second item with the seat price and quantity = extra users.
  - Booking with a running subscription and cancelling are worker jobs (`billing.mara-items`), never Stripe calls in the request.
  - Booking without a subscription goes into the Checkout as extra line items.
  - All changes use `proration_behavior=create_prorations`, so a user added or removed mid-period is billed for the days.
  - Booking ends a running trial: charging starts at once, pro rata.
- **Seats.** Owners, members and technicians count (ADR 0039); tax advisors are free. When someone joins, leaves or changes role, `billing.mara-seats` sets the seat item (create, change, remove). It is idempotent: it compares the current count with what is billed.
- **Webhooks.** Subscription events now read all items. The plan's item stays the plan; the MARA and seat items update `billing_addon` (active, item IDs, billed seats, audit `billing.mara.changed`).
- **Employee data (M-NF-09).**
  - Raw time records (the time-entry list, the payroll CSV) are visible to owners and members only, never to tax advisors.
  - Technicians receive time entries only of work orders they are assigned to. On such a shared job they see colleagues' times, because the report the customer signs contains all of the job's times (canonical report, ADR 0040). This amends ADR 0039's "never other people's"; the lawyer is asked (question 12).
  - Customer documents (report PDF, invoice) keep the job's times.
  - There are no per-person totals, rankings or scores anywhere in the API or the app.
- **Legal texts** (drafts): AVV and privacy notice extended for job, photo, signature and time data; Google FCM and Apple APNs as sub-processors (push token only, third-country transfer named); terms § 10a for the add-on; a works-council notice and an employee information sheet as public pages, linked from the billing page and the app.

## Consequences
- Billing tests and existing MARA tests run with billing on: test workspaces get the trial automatically (`Accounts.registered(maraTrial = true)`); `MaraAddonIT` covers the add-on itself.
- Removing the add-on credits the rest of the period at once; there is no "cancel at period end" for add-ons in the MVP.
- If Stripe and ERIKA disagree (an item removed in the Stripe dashboard), the webhook is authoritative.
- The seat price is a configuration (a Stripe price), so the open pricing decision needs no code change.
