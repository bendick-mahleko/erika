# 0031. Invoice creator: drafts, issuing in the worker, numbering, sending

- Status: Accepted
- Date: 2026-11-23

## Context
Stage 8 adds the invoice creator (CR-01 … CR-07, BR-04, BR-06): customers and articles, a guided form, XRechnung/ZUGFeRD output, sequential numbers, credit notes and cancellations, and sending by email with a delivery log. Software Design §5 Flow C says one transaction locks the counter, assigns the number, generates and validates the file (VALID or WARNING, otherwise roll back), archives it and creates the document. Two existing rules shape where this runs:
- validation runs only in the worker (Software Design §7, `validation.InvoiceValidator`);
- the API never renders or parses files (ADR 0025).

## Decision

### Modules and tables
- The module `creator` owns `invoice_settings`, `party` (customers), `article`, `invoice_draft`, `invoice`, `number_sequence` and `invoice_delivery`. Each is a tenant table with RLS.
- It uses the published interfaces `archive.Archive`, `documents.Documents`, `identity.WorkspaceProfiles` (the seller's name, address and tax IDs, kept in the workspace settings) and `validation.InvoiceValidator`.
- An issued invoice becomes an **outgoing document** (`direction OUT`, `received_via CREATED`) through `Documents.registerOutgoing`. The viewer, the PDF view copy, search and status history therefore work as for received invoices.
- Outgoing statuses (build plan, gap 4): ISSUED, SENT, DELIVERY_FAILED, PAID, CANCELLED, BOOKED. Users may set SENT, PAID and BOOKED; ERIKA sets the rest. The status lives only in `document` (`Documents.changeStatus`), with history and audit.

### Drafts (CR-01, CR-05)
- `invoice_draft.data` holds the form content as JSON. Every field is optional while editing.
- Saves use `PUT` with `If-Match` (autosave in the UI); master data uses ETags as well.
- A chosen customer is **copied** into the draft. Changing the customer later doesn't change drafts or issued invoices.
- `DraftChecks` contains the server-side checks: § 14 Abs. 4 UStG, plus what XRechnung needs (BT-10 buyer reference, buyer email for BT-49, seller contact BG-6) and the VAT-case rules (VAT IDs for AE/K, another EU country for K). The front end runs the same checks for instant feedback.
- `POST /invoices/{id}/issue` answers **422 `DRAFT_INCOMPLETE`** with the list of problems while anything is missing: the form can't be submitted (CR-01).

### Issuing in the worker (Flow C)
- `POST /invoices/{id}/issue` (with `Idempotency-Key`) freezes the draft (`ISSUING`) and queues `creator.issue-invoice`; it answers 202. The client polls until `ISSUED`, or until the draft is back in `DRAFT` with `issueError`.
- The job (`InvoiceIssuer`) runs everything in one transaction:
  1. lock the draft and the counter (`SELECT … FOR UPDATE` on `number_sequence`) and assign the number;
  2. build the neutral model (`InvoiceAssembler`) and write the file (invoice-core ADR 0006);
  3. validate it with the active pack;
  4. archive, create the document and insert `invoice`.
- If the checks or the validation fail, everything rolls back to a savepoint: the counter is untouched, so **no number is lost**. The draft then returns to DRAFT with the messages.
- The file is archived only after validation, so a rejected file never leaves a locked object behind.
- The counter row stays locked until commit. Issues in one workspace are therefore serialised, which is what keeps numbers gap-free (CR-03). `IssueConcurrencyIT` runs 100 parallel issues: 100 unique numbers, 1 … 100.

### Numbering (BR-04)
- Patterns look like `RE-{YYYY}-{NNNN}`: `{YYYY}`, `{YY}` and `{MM}` from the issue date, plus exactly one counter. Credit notes and cancellations have their own pattern (default `GS-{YYYY}-{NNNN}`); the same pattern shares the counter.
- Each sequence key (the pattern with its date parts filled in) has its own counter, so a yearly pattern restarts every year.
- Used numbers are the `invoice` rows (no UPDATE or DELETE for the app role). Cancelled invoices stay; the cancellation is a separate document.

### Credit notes and cancellations (CR-06)
- `POST /invoices/{id}/credit-note` with `CREDIT_NOTE` (381, lines editable), `CANCELLATION` (381, full amount) or `CORRECTION` (384).
- It creates a draft that references the original (BT-25/26, `invoice.original_invoice_id`). Issuing a cancellation or correction sets the original's status to CANCELLED.
- A credit note can't itself be corrected, and an original can be cancelled only once.

### VAT cases (CR-04, BR-06)
- A customer has a VAT treatment: DOMESTIC (line rates 19/7/0 %), REVERSE_CHARGE (AE) or INTRA_EU (K). The Kleinunternehmer flag in the settings overrides it with category E and the § 19 UStG note.
- Notes and exemption reasons come from invoice-core's `VatCase`.
- Sellers with neither a VAT ID nor a register entry are identified by their tax number in BT-29. Without it, EN 16931 BR-CO-26 would reject the file.

### Sending (CR-07)
- `POST /invoices/{id}/send` creates an `invoice_delivery` row and queues `creator.send-invoice`. The job:
  1. builds the message with the archived file attached, using a fixed Message-ID, date and MIME boundary, so a retry builds the same bytes;
  2. **archives the .eml write-once** and records its SHA-256 on the delivery;
  3. sends exactly those bytes (`RawMailTransport`, `Transport.sendMessage` without re-encoding).
- The email and the file are thus archived together: the .eml contains the file.
- The provider's answer ("queued as …") is stored as `provider_message_id`.
- **Delivery results** arrive at `POST /webhooks/mail` (api/webhooks.yaml). The call is authenticated by a shared secret (`ERIKA_MAIL_WEBHOOK_SECRET`) and matched by Message-ID through a SECURITY DEFINER function. A bounce sets the delivery to BOUNCED and the document to DELIVERY_FAILED.
- The outgoing mail provider is not chosen yet (docs/legal/README.md). The webhook therefore takes a provider-neutral event; the provider's own format will be mapped to it.

### Idempotency
- `platform.web.Idempotency` stores `(workspace, Idempotency-Key)` in `idempotency_key` inside the request's transaction. A repeated request returns the resource the first one created.
- If the first request failed, its key rolled back with it.
- The header is required on the POSTs that create or send something in the creator.

## Consequences
- Issuing takes a few seconds (worker poll plus validation; PDF/A checks are the slowest). The UI shows progress and polls.
- Because the counter row stays locked during validation, a burst of issues in one workspace is processed one at a time. That is intended, for gap-free numbers.
- A crash right after an SMTP send can send an email twice (SMTP has no idempotency key). The archived .eml and the delivery log show both.
- Not in Stage 8: company logo (CR-08), recurring invoices (CR-09), Leitweg-ID / B2G (CR-10, the column is reserved), document-level allowances in the form, foreign currencies.
