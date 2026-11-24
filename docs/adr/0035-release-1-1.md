# 0035. Release 1.1: allow-list, reminders, GiroCode, logo, recurring invoices, English

- Status: Accepted
- Date: 2026-11-24

## Context
Stage 12 lists Release 1.1 as additive work on the MVP: IN-06, AR-05, AR-06, CR-08, CR-09 and VW-07 / NF-13. It needs no outside party. The later items need decisions or partners first: XRechnung 4.0 is not published, Peppol needs a certified access point, and DATEV needs the partner programme. They stay open (see "Not in this release").

## Decision

### IN-06 sender allow-list and quarantine (`intake`)
- **Settings.** `intake_settings.allow_list_enabled` (default off) and `intake_sender` hold the patterns: an exact address or a whole domain (`@lieferant.de`). Patterns are stored lower case. Owners change them (`PUT /intake/settings`, `POST /intake/senders` with Idempotency-Key, `DELETE /intake/senders/{senderId}`).
- **Matching.** The worker checks after loading the raw email. Both the envelope sender and the From header are checked; one match is enough.
- **Quarantine.** Email from other senders gets status QUARANTINED, with `from_address` and the subject (shortened) for the list.
  - The raw email is archived either way, because the virus scan and `Archive.store` happen before.
  - Members (not advisors) release it: status back to RECEIVED and `released_at` set, then the job is queued again and skips the check. Releasing can also allow the sender.
  - Discarding sets DISCARDED. The archived original stays, because the archive is write-once.
- **Audit.** Every change and decision is audited.

### AR-05 due-date reminders (`documents`)
- **Job.** A daily job (07:00 Berlin, `documents.due-reminders`) finds received invoices due in 3 days. Paid, booked and disputed invoices are skipped.
- **Email.** Each member who wants reminders gets one email per workspace and day. `membership.notify_due_dates` defaults to on; tax advisors never get reminders. The email is in the member's language.
- **Idempotency.** `due_reminder` (document, due date) makes retries and re-runs send nothing twice. A changed due date gets a new reminder.

### AR-06 GiroCode
- **Transfer details.** `Document.transfer` is filled for incoming invoices that can be paid by SEPA credit transfer in EUR: payee (BT-59, else seller), IBAN, BIC, amount due, and the reference (BT-83, else the invoice number). It also carries the EPC069-12 version 002 payload.
- **QR code.** The web app renders the QR code as SVG on the server (`qrcode`) and adds copy buttons. Nothing new is parsed in the API: the values come from the stored parse result (ADR 0025).

### CR-08 logo and accent colour (`invoice-core` 0.5.0, `creator`)
- **Layout.** `PdfLayout(logo, accentColor)` changes only the readable page of ZUGFeRD files. The XML is unchanged and XRechnung ignores the layout.
  - The logo is a PNG or JPEG of at most 512 KB, checked by its bytes. It is drawn top right on page 1 (at most 160 × 44 pt).
  - The accent colour `#RRGGBB` is used for the title and the table rule.
- **Storage.** The logo is kept in `invoice_settings.logo` (bytea) because it is small and per workspace. It is not a document, so it is not in the archive. Owners upload it as multipart (`PUT /invoice-settings/logo`).
- **Conformance.** `GeneratedInvoicesConformanceIT` covers a transparent PNG and a JPEG with veraPDF.
- **Issued invoices.** They keep the logo they were issued with, because the archived file never changes.

### CR-09 recurring invoices (`creator`)
- **Template.** `recurring_invoice` stores a copy of the draft content (without dates), the interval (MONTHLY, QUARTERLY) and `next_run`. It is created from an invoice or draft of kind INVOICE.
- **Runs.** A daily job (05:30 Berlin, `creator.recurring-invoices`) creates one **draft** per due run. Each draft gets the service period [run, run + interval − 1 day] and an email goes to the creator.
  - The job catches up missed runs, at most 12 per template.
  - Rows are locked with `FOR UPDATE SKIP LOCKED` and `next_run` advances in the same transaction, which makes the job idempotent.
- **Issuing.** Drafts are **never issued automatically**: a person checks them, and numbering and validation stay in `InvoiceIssuer`.
- **Audit and locking.** Templates use ETag/If-Match. Creating, changing and running them is audited.

### VW-07 / NF-13 English
- **App language.** The web app's language comes from the `NEXT_LOCALE` cookie. A switcher in the header sets it and, when the user is signed in, saves it for emails (`POST /auth/locale`, any role: it is the user's own setting). German stays the default, and both message files have the same keys.
- **English view.** The worker builds an English view next to the German one (`parsed_invoice.view_en`, `InvoiceView.of(invoice, EN)`). It has English labels for every BT/BG, English code-list texts, ISO dates and `1,234.56` numbers.
  - `GET /documents/{id}/view?lang=en` falls back to German for documents read before 1.1, and `language` in the answer says which one was sent.
  - The PDF view copy stays German: it is the reviewed representation (ADR 0025).

## Not in this release (open decisions)
- **XRechnung 4.0.** It is not published yet. When it is, it goes through the NF-15 pack process within 30 days.
- **Release 2.** Each item needs a decision or a contract first:
  - Peppol needs a choice of certified access point.
  - The public API with keys and webhooks needs a pricing and abuse policy.
  - White-label for tax advisors needs a partner model.
  - Leitweg-ID and B2G
- **Release 3.** The DATEV interface needs the DATEV partner programme. invoice-core 1.0 depends on the trades app's needs.

## Consequences
- Five new tenant tables with RLS. `TenancyDatabaseIT`, `TenantIsolationIT` and `AdvisorReadOnlyIT` cover them; the harnesses now handle multipart bodies.
- Two more daily jobs. Both are idempotent and safe to run more than once.
- The English view doubles the stored view JSON per document, which is small compared with the originals.
