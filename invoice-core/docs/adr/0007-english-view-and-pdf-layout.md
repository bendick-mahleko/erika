# 0007. English view and PDF layout options

- Status: Accepted
- Date: 2026-11-24

## Context
ERIKA Release 1.1 needs an English invoice view (VW-07) and the seller's logo and accent colour on generated ZUGFeRD invoices (CR-08). The library must stay neutral: no ERIKA settings, no storage.

## Decision
- **Language.** `ViewLanguage { DE, EN }` and `InvoiceView.of(invoice, language = DE)`.
  - English uses `BtLabelsEn` (one label for every BT/BG id, the same ids as the German table, checked by `InvoiceViewEnglishTest`) and English code-list texts (`CodeLists.of(list, language)`).
  - English formats: ISO dates (2026-11-24) and `1,234.56` numbers.
  - German stays the default, and existing callers are unchanged.
- **Layout.** `PdfLayout(logo, accentColor)` with `InvoiceWriter.write(invoice, format, layout)`; the old overload uses `PdfLayout.DEFAULT`.
  - The logo must be a PNG or JPEG of at most `MAX_LOGO_BYTES` (512 KB), detected by its bytes (`PdfLayout.imageType`). It is drawn top right on page 1 (at most 160 × 44 pt, aspect ratio kept).
  - The accent colour (`#RRGGBB`) is used for the title and the table rule.
  - The layout changes only the readable page. XML output is byte-identical, and XRechnung ignores the layout.
- **XMP.** The XMP packet header now writes the BOM as an explicit escape, so formatters can't strip it.

## Consequences
- Version 0.5.0 (additive; SemVer minor).
- ERIKA's `GeneratedInvoicesConformanceIT` runs veraPDF on ZUGFeRD files with a transparent PNG and a JPEG logo.
