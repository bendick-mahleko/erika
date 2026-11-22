# invoice-core – notes for Claude Code

Plain Kotlin library (no Spring, no DB), in the `invoice-core/` folder of the ERIKA repository but built on its own (run Gradle from this folder), used by ERIKA and later the trades/Nebenkosten apps. Decisions: `docs/adr/`.

## Commands
- `./gradlew check` – ktlint + tests + 90 % coverage gate (first run downloads the pinned test suites).
- `./gradlew test -PupdateGolden` – rewrite golden files after an intended model change; review the diff before committing.
- `./gradlew spikeTest` – spike S2-a (Mustangproject + veraPDF), slow, not part of `check`.

## Rules
- Explicit API mode: keep internals `internal`; public changes follow SemVer.
- Mappings follow EN 16931-3-2 (UBL) / 3-3 (CII). Any mapping change must keep `CrossSyntaxEquivalenceTest` green; never widen its ignore lists without a documented test-data reason.
- Every new model property gets `@Bt(...)`.
- Parse warnings and exception messages must never contain invoice content.
- New content-check rules: stable `ERIKA-…` ID + German and English text in `RuleTexts`.
