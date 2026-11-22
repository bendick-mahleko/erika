# 0003. Library API, explicit API mode and semantic versioning

- Status: Accepted
- Date: 2026-11-22

## Decision
- Kotlin explicit API mode: every public declaration is deliberate; internals (`xml`, `ubl`, `cii`, `pdf`) are `internal`.
- Public surface: `InvoiceParser`, `InvoiceParseException`/`ParseErrorCode`, the `model` package, `check` (`InvoiceChecks`, `ArithmeticChecks`, `MandatoryContentChecks`, `Finding`, `RuleTexts`).
- Semantic versioning, starting at 0.1.0; 1.0 before a second app depends on it. Published to GitHub Packages on `invoice-core-v*` tags (the library lives in the ERIKA repository, see ERIKA ADR 0016).
- The only runtime dependency is PDFBox.
- Format values (`InvoiceFormat`) extend the design's list (`XRECHNUNG_UBL`, `XRECHNUNG_CII`, `ZUGFERD`, `PDF_ONLY`) with `UBL`, `CII` (other EN 16931 invoices such as Peppol BIS) and `UNSUPPORTED` (ZUGFeRD 1.0, unknown XML).
- Parsing is lenient (nullable fields); whether content is complete is decided by the checks and the format validation, not by the parser.

## Consequences
The ERIKA `document.format` column must accept the extra values (Stage 5 migration).
