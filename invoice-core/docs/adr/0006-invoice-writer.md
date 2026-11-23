# 0006. Invoice writer: own CII/UBL serialisers and PDF/A-3 embedding

- Status: Accepted
- Date: 2026-11-23
- Supersedes the generation part of [0004](0004-spike-zugferd-generation.md)

## Context
ERIKA Stage 8 needs `InvoiceWriter.write(model, format)` for XRechnung CII, XRechnung UBL and ZUGFeRD profile EN 16931 (CR-02). Spike S2-a showed that Mustangproject plus veraPDF produce valid PDF/A-3b output, and 0004 planned to use Mustangproject for generation.

Three things argue against Mustangproject as a library dependency:
- It writes CII only. UBL output would need our own serialiser anyway.
- It has its own invoice model. We would map the neutral model to Mustang's and lose control over which business terms are written (BR-DE rules, BR-CO-26, intra-EU delivery country).
- It pulls in a large dependency tree. Today the library depends on PDFBox only, which keeps the trades and Nebenkosten apps light.

## Decision
- **Own serialisers** in the package `write`: `CiiWriter` (UN/CEFACT CII D16B, EN 16931-3-3) and `UblWriter` (UBL 2.1 Invoice, or CreditNote for type code 381, EN 16931-3-2). Each is the inverse of the existing mapper, in schema element order. A small deterministic XML writer (`XmlOut`) leaves out empty elements and escapes text.
- **ZUGFeRD/Factur-X** (`ZugferdPdf`): PDFBox renders a readable A4 invoice and embeds the CII XML as `factur-x.xml` with `AFRelationship=Alternative`. It also writes the catalogue `AF` array, the Factur-X XMP extension schema (`fx:` DocumentType INVOICE, ConformanceLevel EN 16931), an sRGB output intent (the JDK profile) and the embedded Liberation Sans font that ships with PDFBox.
- **Amounts** come from `InvoiceCalculator` (BR-05): line net = quantity × price ÷ base quantity, rounded to two decimals; VAT per category and rate, rounded per breakdown (BR-CO-17); totals BR-CO-10…16. Rounding is half up.
- **VAT cases** (`VatCase`): S 19 %, S 7 %, Z, AE, K and E. Each carries its category code, rate, VATEX code, exemption reason (BT-120) and the note required by the UStG (§ 13b, § 6a, § 19).
- **Deterministic output.** The same invoice gives the same bytes: dates come from the invoice, and the PDF document ID is derived from the XML. A retried issue job therefore archives the same original.
- **Public API (0.4.0, minor):** `InvoiceWriter` (`write`, `prepare`, `fileName`), `OutputFormat`, `InvoiceCalculator` and `VatCase`.
- Mustangproject stays in the spike source set only. It is not used at run time.

## Verification
- `InvoiceWriterTest` writes every format × VAT case × type code (380, 381, 384) and reads each file back with our own parser. It checks the detected format and profile, the same model as written, and that the content checks find no errors. It also covers determinism, escaping, the PDF/A structure and multi-page layout.
- In the ERIKA back end, `GeneratedInvoicesConformanceIT` validates the same 54 files with the KoSIT configuration (XRechnung 3.0.2, EN 16931 CII/UBL) and veraPDF (PDF/A-3). Result: 0 errors.

## Consequences
- Adding a business term to the creator means adding it to both serialisers and to the round-trip test data.
- The visual PDF uses one font face (Liberation Sans Regular); characters outside it print as `?`, while the XML keeps them. A company logo (CR-08, Should) is a later minor version.
