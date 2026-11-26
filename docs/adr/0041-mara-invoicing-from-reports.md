# 0041. MARA: invoicing from reports through ERIKA's invoice creator

- Status: Accepted (MARA Stage 8); the § 35a wording and the example invoices await the tax advisor's check
- Date: 2026-11-26

## Context
Signed job reports (ADR 0040) are what a trade firm bills. ERIKA already issues e-invoices: `creator.InvoiceIssuer` assigns the number, generates the file, validates it with KoSIT and veraPDF and archives it, in one transaction in the worker (ADR 0031). MARA must reuse that path, not build a second one. The build plan asks for the report PDF inside the invoice, no double billing, the § 35a EStG labour share and the § 14 (4) Nr. 9 UStG retention note for private customers, payment reminders for outgoing invoices, and a payroll export of times.

## Decision
- **One issuing path.** `reportinvoicing` builds the lines and calls `creator.SourceInvoices.create` (published API, in the request's tenant transaction). It writes a draft and requests issuing; `InvoiceIssuer` does the rest in the worker as for any other invoice. Lines can be changed by the office before the invoice is created; afterwards the invoice never changes (corrections by credit note, ADR 0031).
- **No double billing.** `invoice_draft_source` (creator) records the source module and record IDs with a primary key on (workspace, module, source): a second invoice for the same report fails with 409 `ALREADY_INVOICED`. The rows go with the draft while it is still a draft (`ON DELETE CASCADE`), so deleting a failed draft frees its reports.
- **Line rules are data-tested.** `ReportLineRules` follows `mara-rules/vectors/invoicing/lines.json`: each time entry is rounded up to the billing unit on its exact duration (raw times stay unchanged, M-BR-04), then summed per report and hourly rate; the hourly rate is the technician's staff level, else the firm's default; travel is billed by time, as a flat amount per report (visit) or not at all (M-BR-05), per firm with per-customer overrides; materials use the article's net price and free-text materials must be priced by the office. The rule runs on the server only, so there is no Dart implementation.
- **The report PDF is embedded (BG-24).** invoice-core 0.6.0 writes supporting documents in CII (`ram:AdditionalReferencedDocument`, type code 916) and UBL (`cac:AdditionalDocumentReference`). The draft holds only the archived PDF's SHA-256; `InvoiceIssuer` reads it through `Archive.open` (hash-checked) while generating. Reference = the report's verification code, file name `Arbeitsbericht-<code>.pdf`. `GeneratedInvoicesConformanceIT` checks every format and VAT case with an attachment.
- **Private customers (M-BR-07).**
  - They get ZUGFeRD (EN 16931): a PDF/A-3 that reads like a normal invoice, so no second PDF generator.
  - Lines carry `laborCost`; work and travel lines are labour by default. The assembler adds a note "Im Rechnungsbetrag sind Arbeits- und Fahrtkosten nach § 35a EStG von … enthalten" with net plus VAT of those lines.
  - For work on a property (`propertyRelated`), the note on the customer's two-year retention duty (§ 14b Abs. 1 Satz 5 UStG) is added.
  - Both are invoice notes (BT-22), so they are in the XML and on the PDF.
- **Work order status.** `report_invoice_link` maps reports to work orders and the invoice; a listener on creator's `InvoiceIssued` (in the issuing transaction) sets the work orders to INVOICED. Sent, paid and overdue come from the invoice's document status, shown on the report.
- **Payment reminders (M-IV-06).** `GET /invoices/overdue` lists issued invoices past their due date that aren't paid, booked or cancelled. A reminder is an email of the invoice with a reminder text through the existing delivery path (`invoice_delivery`), recorded in `invoice_reminder`: level 1 Zahlungserinnerung, levels 2 and 3 Mahnung; no more than three. Letters by post are cut (build plan §8).
- **Payroll export (M-IV-07).** `GET /reports/time-entries.csv` (owner only): raw times, semicolon-separated with a BOM for German Excel; formula-like cells are prefixed against CSV injection.
- **Firm settings (M-AC-03).** The logo from `invoice_settings` is also drawn on report PDFs.
- **Export (M-AC-04).** `creator` exports draft sources and reminders; `reportinvoicing` exports its settings and links (`berichtsabrechnung.json`).

## Consequences
- Invoices from reports pass the same validation, numbering and archive as all ERIKA invoices; there is no MARA-specific invoice code to certify.
- A report PDF must be archived before its invoice can be created (409 `REPORT_PDF_PENDING`); the worker usually does this within seconds of signing.
- Invoices with embedded PDFs are bigger (one report PDF is typically 100–500 KB); this counts against the storage allowance.
- The § 35a wording and the example invoices in `docs/mara/examples/` need the tax advisor's confirmation before go-live.
