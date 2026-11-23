# Runbook: updating the validation rules (NF-15)

NF-15: a new official release is supported **within 30 days**. Background: [ADR 0007](../adr/0007-validation-packs-as-data.md), [ADR 0021](../adr/0021-validation-packs-and-engine.md), [validation-packs/README.md](../../validation-packs/README.md).

## Watch for releases

Check monthly, as part of the release checklist, and subscribe to the release notifications ("Watch → Custom → Releases") of these sources:

| Source | What | Where |
|---|---|---|
| KoSIT validator configuration XRechnung | Schemas and Schematron, `scenarios.xml` | github.com/itplr-kosit/validator-configuration-xrechnung/releases |
| KoSIT validator | Library `org.kosit:validator` | github.com/itplr-kosit/validator/releases |
| XRechnung test suite | Official test files | github.com/itplr-kosit/xrechnung-testsuite/releases |
| CEN EN 16931 validation artefacts | CII/UBL Schematron | github.com/ConnectingEurope/eInvoicing-EN16931/releases |
| FeRD ZUGFeRD / Factur-X | Profiles and schemas | ferd-net.de (news and downloads) |

When a release appears, open an issue "Validation pack <name>" with the release date and the deadline (release + 30 days).

## Steps

1. **Read the release notes.** Which rules are new, changed or removed? Is a new validator version required? Does invoice-core's parser need changes (new elements, new syntax version)? A major version (XRechnung 4.0) runs in parallel with 3.0.x; plan it as a project (build plan Stage 12).
2. **New pack:** `validation-packs/<name>_<config date>/`.
   - `pack.json`: new artefact URL, its SHA-256 (`sha256sum <zip>`), `activeFrom` set to the planned activation date (at least a few days after deployment, so the rules don't change during a customer's working day).
   - Copy `erika-scenarios.xml` and adapt it if the scenario structure changed.
   - Never commit the downloaded artefacts (CLAUDE.md).
3. **Assemble and generate the texts:**
   ```sh
   cd backend && ./gradlew prepareValidationPacks
   python ../validation-packs/tools/rule_texts.py build/validation-packs/<id> ../validation-packs/<id>
   ```
   Write German texts for every new business rule in `validation-packs/rule-texts/de.json`: plain language, what's wrong and what the supplier must change. Run the script until it reports no missing IDs.
4. **Test suite:** update the pinned version of the official test files (backend `build.gradle.kts` and invoice-core) if a new one was released with the rules.
5. **Verify:** `./gradlew check`. `ConformanceIT` must match the KoSIT reference on 100 % of the official test files, and `RuleTextCatalogueTest` requires a German text for every rule.
6. **Release** as usual ([release-checklist.md](release-checklist.md)), with a changelog entry "Neue Prüfregeln <name>, gültig ab <date>".
7. **On the activation date:** check in Grafana / GlitchTip that validation runs without new errors. Run spot checks: a few new incoming invoices show the new `packId` in the validation panel.
8. **Optional:** re-check open documents with the new rules ("Erneut prüfen", or in bulk via SQL as `erika_owner`: queue `documents.parse-and-validate` jobs). Earlier results stay in the history (BR-07).
9. Close the issue with the dates. The time from release to activation is the NF-15 measure, and it must stay under 30 days.

## If something goes wrong
- **Conformance differences:** first check whether ERIKA's own scenarios (`erika-scenarios.xml`) need adapting. Never change the official configuration.
- **A wrong rule result after activation:** set `activeFrom` of the new pack to a later date and release again. The previous pack takes over, and results checked meanwhile stay explainable through their `packId`.
