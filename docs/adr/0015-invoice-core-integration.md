# 0015. How ERIKA uses invoice-core

- Status: Accepted (location amended by 0016; dependency by 0020: built from source, not a released version)
- Date: 2026-11-22

## Context
`invoice-core` (Stage 2, now in the folder `invoice-core/`, see ADR 0016) parses and checks e-invoices. Its own decisions are in `invoice-core/docs/adr`. This ADR records what follows for the ERIKA app.

## Decision
- The back end depends on a **fixed released version** (`de.erika:invoice-core:0.1.x`) from GitHub Packages; locally via `publishToMavenLocal`. The dependency is added in Stage 4/5, when `validation` and `intake` first use it, not before.
- `document.format` stores `InvoiceFormat` names. The design lists `XRECHNUNG_UBL`, `XRECHNUNG_CII`, `ZUGFERD`, `PDF_ONLY`; invoice-core adds `UBL`, `CII` (other EN 16931 invoices such as Peppol BIS) and `UNSUPPORTED` (ZUGFeRD 1.0, unknown XML). The Stage 5 migration allows all seven.
- `parsed_invoice.data` (JSONB) stores the neutral `Invoice` model; the indexed search columns are taken from it.
- Content findings (`ERIKA-ARITH-*`, `ERIKA-USTG-*`) are stored with the validation result next to the KoSIT rule results; their texts come from `RuleTexts` (German first).
- MINIMUM and BASIC-WL invoices are parsed and shown, labelled "not a valid e-invoice" (`DetectedFormat.isEInvoice == false`).
- Parsing runs only in the worker, with a time limit (PDFBox memory is capped inside invoice-core).

## Consequences
- An invoice-core release is needed before ERIKA can use a change; contract tests in invoice-core protect ERIKA.
- The creator (Stage 8) must make the seller contact (BG-6) mandatory for XRechnung output (finding of spike S2-a).
