# Validation packs

Versioned validation rules, used at runtime by the `validation` module (ADR 0007, 0021; NF-15, BR-07).

```
validation-packs/
  xrechnung-3.0.2_2026-08-31/
    pack.json              id, title, activation date, artefacts (URL + SHA-256), configurations
    erika-scenarios.xml    ERIKA's extra KoSIT scenarios (ZUGFeRD BASIC/EXTENDED, Peppol BIS, other CIUS)
    rule-texts.de.json     German text for every rule ID of the pack (generated, see below)
  rule-texts/de.json       hand-written German texts for the business rules (pack-independent)
  tools/rule_texts.py      builds a pack's rule-texts.de.json
```

Third-party artefacts (the KoSIT configuration with XML Schemas and Schematron) are **not** committed.
`cd backend && ./gradlew prepareValidationPacks` downloads them, checks their SHA-256 and assembles
`backend/build/validation-packs/<id>/` (in the Docker image: `/app/validation-packs`).

## Adding a new official release

1. Create `validation-packs/<new id>/` with `pack.json` (new artefact URL and SHA-256, `activeFrom` =
   planned activation date) and a copy of `erika-scenarios.xml`.
2. `cd backend && ./gradlew prepareValidationPacks`
3. `python validation-packs/tools/rule_texts.py backend/build/validation-packs/<new id> validation-packs/<new id>`.
   The script lists rule IDs without a German text; add those to `rule-texts/de.json` (or extend the
   templates/patterns in the script) and run it again until it passes.
4. `./gradlew check` runs the conformance test against the official test files and the rule-text check.
5. Deploy. The new pack takes over on its `activeFrom` date; old packs stay, so earlier results remain explainable.
