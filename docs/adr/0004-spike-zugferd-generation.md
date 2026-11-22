# 0004. Spike S2-a: ZUGFeRD PDF/A-3 generation with Mustangproject

- Status: Accepted
- Date: 2026-11-22

## Question
Do Mustangproject and veraPDF give stable PDF/A-3b output for ERIKA's ZUGFeRD cases (Software Design, open point 2)?

## Method
`src/spike/.../ZugferdGenerationSpike.kt`, run with `./gradlew spikeTest`:
1. Build a PDF/A-3b visual part with PDFBox (embedded Liberation Sans, sRGB output intent, XMP `pdfaid` part 3 / B).
2. `ZUGFeRDExporterFromPDFA` (Mustangproject 2.26.0) embeds the XML: profiles EN16931 and XRECHNUNG.
3. `ZUGFeRDValidator` (Mustangproject 2.26.0 with veraPDF 1.30.2) checks PDF/A-3 and XML (XSD + Schematron, incl. XRechnung rules).
4. `InvoiceParser` reads the result back; `InvoiceChecks` must report no errors.
5. A second export of the same data must give identical XML.

## Results (2026-11-22)
| Case | PDF/A-3 (veraPDF) | XML rules | Read back | Deterministic |
|---|---|---|---|---|
| 19 % VAT, EN16931 (`factur-x.xml`) | valid | valid | yes, total 963.78 | yes |
| 7 % VAT, EN16931 | valid | valid | yes, total 866.59 | yes |
| 19 % VAT, XRECHNUNG (`xrechnung.xml`) | valid | valid | yes | yes |

Notes:
- The first XRechnung attempt was invalid (BR-DE-2: seller contact BG-6 missing). The ERIKA creator must make BG-6 mandatory for XRechnung output (CR-01).
- In the very first run the read-back printed a total of 963 instead of 963.78 for the XRechnung case; this did not reproduce in later runs (the file on disk had 963.78). Keep an eye on it: Stage 8's round-trip tests compare totals for every case.
- Mustangproject 2.26 uses PDFBox 3.0.8 — the same version as invoice-core, so no conflict.

## Decision
Use Mustangproject + PDFBox for ZUGFeRD generation in Stage 8, with ERIKA rendering its own PDF/A-3b visual part. Validate every generated file with the KoSIT configuration (Stage 4) and veraPDF before issuing.

## Not yet covered (Stage 8)
Reverse charge (AE), intra-EU (K), Kleinunternehmer (E, § 19 UStG), credit note (381), cancellation, allowances/charges, UBL output, company logo (CR-08).
