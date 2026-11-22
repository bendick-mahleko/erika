# 0021. Validation packs and the validation engine

- Status: Accepted
- Date: 2026-11-22

## Context
VW-03 requires the same result as the official KoSIT validator. BR-07 requires knowing which rule versions were applied. NF-15 requires updating the rules within 30 days of a new release. VW-04 requires a German explanation for every rule. ADR 0007 decided to treat validation packs as versioned data. ZUGFeRD needs the EN 16931 CII rules and a PDF/A-3 check, which the KoSIT XRechnung configuration doesn't cover by itself (build plan §6, gap 2).

## Decision
**Packs.**
- One directory per pack: `validation-packs/<id>/`, for example `xrechnung-3.0.2_2026-08-31`. It contains:
  - `pack.json`: id, title, activation date (`activeFrom`), and the artefacts, each with URL and SHA-256;
  - `erika-scenarios.xml`: ERIKA's additional KoSIT scenarios;
  - `rule-texts.de.json`: German texts.
- Third-party artefacts aren't committed. `./gradlew prepareValidationPacks` downloads them, verifies them by SHA-256, caches them, and assembles `backend/build/validation-packs/` (in the image: `/app/validation-packs`).
- The pack in force is the newest one whose `activeFrom` has passed. Old packs stay available, and every validation report carries the `packId`.

**Engine** (worker only):
1. invoice-core detects format and profile. Plain PDFs, MINIMUM, BASIC-WL, ZUGFeRD 1.0 and unknown XML are labelled "not applicable", not validated.
2. The KoSIT validator 1.6.3 (`org.kosit:validator`) runs in-process with two configurations:
   - the official XRechnung configuration, unchanged;
   - ERIKA's scenarios, for EN 16931 invoices with other specification identifiers: ZUGFeRD/Factur-X BASIC and EXTENDED, and Peppol BIS. These use the same XML Schemas and CEN EN 16931 Schematron from the official configuration.

   ZUGFeRD EN16931 and XRECHNUNG profiles are already matched by the official scenarios. ERIKA applies the scenario's custom levels the way KoSIT's own assessment does.
3. For ZUGFeRD/Factur-X, veraPDF 1.30 (`validation-model-jakarta`) checks PDF/A-3B.
4. invoice-core's content checks (arithmetic, § 14 UStG) run on the neutral model.

   The overall level is the worst message: ERROR, WARNING or VALID.

**Rule texts.**
- Every rule ID gets a German text. `validation-packs/tools/rule_texts.py` builds a pack's catalogue from three sources:
  - hand-written texts for the business rules (`validation-packs/rule-texts/de.json`, pack-independent);
  - templates for the VAT-category and decimal rules;
  - patterns for the purely syntactic rules (element not intended, at most once).
- `RuleTextCatalogueTest` fails the build if any rule ID of any pack lacks a German text. The rule's own text (mostly English) is kept as `originalText` for experts.

**Conformance.** `ConformanceIT` validates every file of the official XRechnung test suite twice:
- with the official configuration alone, the reference;
- with ERIKA's full pipeline.

It requires the same scenario, the same accept/reject, the same set of (rule, level) messages, and no ERIKA errors on files the reference accepts.

## Consequences
- A new KoSIT release means:
  1. add a pack directory with a new `pack.json` and `erika-scenarios.xml`;
  2. run `./gradlew prepareValidationPacks` and the rule-text tool, then write German texts for new business rules;
  3. CI runs the conformance and catalogue tests;
  4. deploy with the activation date.

  If the release needs a newer validator library, the library is upgraded in code (one version for all packs).
- The reference in `ConformanceIT` is the KoSIT validator library with the unmodified official configuration, not the standalone command-line jar. Both are the same code; the CLI only adds argument parsing and file handling. Running the downloaded standalone jar was blocked by the development environment's permission policy.
- ZUGFeRD BASIC and EXTENDED are checked against the EN 16931 core rules, not yet against the profile-specific Factur-X Schematron. Extension elements produce warnings, not errors. Adding the FeRD/Factur-X artefacts as another pack configuration is planned once a FeRD package with a usable licence and download URL is pinned.
- veraPDF runs in the worker's JVM. A very large or hostile PDF is bounded by the 20 MB limit and the job's retry and lease handling. A separate process with its own memory limit stays an option if this proves insufficient.
