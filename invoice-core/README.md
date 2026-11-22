# invoice-core

The e-invoice engine shared by ERIKA, the Nebenkosten app and the trades app. A plain Kotlin/JVM library: no Spring, no database.

| Capability | Requirement | Status |
|---|---|---|
| Read XRechnung (UBL, CII), EN 16931 UBL/CII, ZUGFeRD 2.x / Factur-X PDFs into one neutral model | IN-03, VW-01 | 0.1 |
| Detect format and profile; label plain PDFs, MINIMUM/BASIC-WL and ZUGFeRD 1.0 | IN-03, IN-04 | 0.1 |
| Arithmetic checks and § 14 UStG mandatory-content checks with German/English texts | VW-05, VW-04 | 0.1 |
| Write XRechnung / ZUGFeRD | CR-02 | Stage 8 |
| Render the PDF view copy | VW-02 | Stage 6 |

## Usage

```kotlin
val parsed = InvoiceParser.parse(bytes)          // throws InvoiceParseException if unreadable
when {
    parsed.invoice == null -> show(parsed.format.reason)       // PDF_ONLY, ZUGFeRD 1.0, unknown XML
    !parsed.format.isEInvoice -> label("not a valid e-invoice") // e.g. MINIMUM, BASIC-WL
}
val findings = InvoiceChecks.check(parsed.invoice!!)           // arithmetic + § 14 UStG
findings.forEach { println("${it.severity} ${it.ruleId}: ${it.message("de")}") }
```

Every model property carries its EN 16931 business term (`@Bt("BT-1")`), used for labels in the viewer.

Limits (Software Design §7): 20 MB per file, 5,000 lines, DTDs rejected (XXE, billion laughs).

## Build

Java 21, Gradle wrapper.

```bash
./gradlew check            # ktlint, all tests, 90 % line-coverage gate (Kover)
./gradlew test -PupdateGolden   # rewrite golden files after an intended model change; review the diff
./gradlew spikeTest        # spike S2-a: ZUGFeRD generation with Mustangproject + veraPDF (slow, not in check)
./gradlew publishToMavenLocal
```

The first `test` run downloads the official test suites (about 40 MB), pinned by version and SHA-256 in `build.gradle.kts`, into the Gradle user home:

| Suite | Version | Used for |
|---|---|---|
| KoSIT XRechnung test suite | 3.0.2, 2026-08-31 | detection, parsing, cross-syntax equivalence, arithmetic, golden files |
| CEN EN 16931 examples | validation 1.3.17 | parsing, golden files |
| ZUGFeRD corpus | commit `02d4b56` | ZUGFeRD 2.x/Factur-X PDFs and profiles, ZUGFeRD 1.0, plain PDF |

## Tests

- **Conformance**: every file of the suites is detected and parsed; valid XRechnung invoices have no content errors.
- **Cross-syntax equivalence**: each XRechnung business case exists in UBL and CII; both must give the same model. Known test-data inconsistencies are listed explicitly in the test.
- **Golden files** (`src/test/resources/golden`): reviewed snapshots of the parsed model; any change shows up as a diff.
- **Model completeness**: every BT-1…BT-165 and BG-1…BG-32 of EN 16931 has a model property.
- **Security**: XXE, billion laughs, size and line limits, broken and encrypted PDFs.

## Versioning and publishing

Semantic versioning (`version` in `gradle.properties`); 0.x until the API settles. Tag `v0.1.0` → CI publishes `de.erika:invoice-core` to GitHub Packages. Decisions: [docs/adr](docs/adr).
