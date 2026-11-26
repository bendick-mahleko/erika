# Example invoices from MARA reports (Stage 8)

For the tax advisor's check of the § 35a EStG output and the § 14b UStG retention note (ADR 0041). Each file is a ZUGFeRD (EN 16931) invoice exactly as ERIKA issued it from a signed test report: numbered, validated (KoSIT, veraPDF) and with the signed report PDF embedded as a supporting document (BG-24). Made by `ReportInvoicingIT` with `ERIKA_EXAMPLES_DIR` set; the data is fictitious.

| File | Customer | What to check |
|---|---|---|
| `Rechnung-Geschaeftskunde-ZUGFeRD.pdf` | Business | No § 35a note; work by the quarter hour, flat travel amount, a material priced by the office |
| `Rechnung-Privatkunde-35a-ZUGFeRD.pdf` | Private, no work on a property | § 35a note with the labour and travel share (net + VAT); no retention note |
| `Rechnung-Privatkunde-35a-Grundstueck-ZUGFeRD.pdf` | Private, work on a property | § 35a note and the two-year retention note (§ 14b Abs. 1 Satz 5 UStG) |

The embedded report PDF can be opened in any e-invoice viewer that shows attachments (e.g. ERIKA's viewer, or the attachments panel of a PDF reader).
