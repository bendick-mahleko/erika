# Traceability: the 26 Must requirements and the MVP acceptance checklist

As of 24 Nov 2026 (Stage 11). Source: Requirements v1.1 §§ 4–8.

Status:
- ✅ verified by an automated test, which runs in CI;
- 🧑 needs a person (manual test or review), protocol linked;
- ⏳ verified in tests but still waiting for an external party.

## Must requirements

| Req | What | Verified by | Status |
|---|---|---|---|
| IN-01 | Email intake, document in the inbox < 2 min | `IntakeIT` (email → inbox), Playwright `intake.spec.ts` via Postfix | ✅ |
| IN-02 | ZIP with 10 files → 10 items | `IntakeIT`, `intake.spec.ts` | ✅ |
| IN-03 | Format detection on all official files | invoice-core conformance suite (XRechnung and ZUGFeRD test files) | ✅ |
| IN-04 | Label and explanation for non-e-invoices | `IntakeIT`, `intake.spec.ts` | ✅ |
| IN-05 | Re-upload / same invoice → "possible duplicate" | `IntakeIT`, `intake.spec.ts` | ✅ |
| VW-01 | Every business term with a German label | Golden files (invoice-core), `DocumentsIT`, `viewer.spec.ts` | ✅ |
| VW-02 | PDF view copy marked, with all values | `DocumentsIT` (PDF text extraction) | ✅ |
| VW-03 | Validation matches KoSIT 100 % | `ConformanceIT` (all official files), `GeneratedInvoicesConformanceIT` | ✅ |
| VW-04 | Every rule ID has a German text | `RuleTextCatalogueTest` | ✅ |
| VW-05 | Wrong total → red | invoice-core arithmetic checks, `ValidationIT` | ✅ |
| VW-06 | Attachments byte-identical, download only | `DocumentsIT`, `MaliciousFileCorpusIT` (JavaScript PDF) | ✅ |
| AR-01 | Recomputed hash = stored hash | `ArchiveIT`, integrity job | ✅ |
| AR-02 | Delete blocked; status changes audited | `ArchiveIT`, `DatabaseRolesIT`, `DocumentsIT` | ✅ |
| AR-03 | History with user and time | `DocumentsIT`, `viewer.spec.ts` | ✅ |
| AR-04 | Search over 10,000 invoices < 1 s | `DocumentsIT` (10k seed); k6 `browse.js` (50 users) | ✅ ([load-test results](../ops/load-test-results.md)) |
| CR-01 | Missing mandatory fields block submitting | `CreatorIT`, `creator.spec.ts` | ✅ |
| CR-02 | Generated files: 0 validation errors | `GeneratedInvoicesConformanceIT` (54 files) | ✅ |
| CR-03 | Parallel issues → unique, gap-free numbers | `IssueConcurrencyIT` (100 parallel) | ✅ |
| CR-04 | VAT cases | `CreatorRulesTest` (one row per case) | ✅ |
| CR-05 | Invoice from saved data < 3 min | `creator.spec.ts` (≈ 30 s); **timed test with a real user** | 🧑 [usability protocol](usability-nf12.md), task 4 |
| CR-06 | Credit note: type code and BT-25 | `CreatorIT`, invoice-core writer tests | ✅ |
| CR-07 | Email and file archived together | `CreatorIT` | ✅ |
| EX-01 | Tax advisor's DATEV import accepts the file | `ExportIT`, `DatevCsvTest`; **import by a tax advisor (S9-a)** | ⏳ [tax-advisor review](../legal/tax-advisor-review.md) |
| EX-02 | Advisor → 403 on every write | `AdvisorReadOnlyIT` (generated), `TeamIT`, `team.spec.ts` | ✅ |
| EX-03 | Stripe test clock; ERIKA invoice passes VW-03 | `BillingIT` (signed webhooks, fake Stripe); **test-clock run on staging** | ⏳ [docs/ops/billing.md §5](../ops/billing.md) |
| EX-04 | Full export complete; closed account read-only | `ExportIT`, `TeamIT`, `team.spec.ts` | ✅ |

## Non-functional requirements

| Req | Verified by | Status |
|---|---|---|
| NF-01 Hosting in the EU, data-processing agreements | ADR 0027; [docs/legal/README.md](../legal/README.md) | 🧑 agreements to sign (Hetzner, mail provider, Stripe) |
| NF-02 TLS, AES-256, 2FA, Argon2; ASVS L1 | [ASVS L1](../security/asvs-l1.md); ZAP and Trivy in CI | ✅, except F-06 (object storage encryption at rest, operator) |
| NF-03 Tenant isolation | `TenantIsolationIT`, `TenancyDatabaseIT` | ✅ |
| NF-04 Virus scan, hardened XML | `IntakeIT` (EICAR, zip bomb), `MaliciousFileCorpusIT` | ✅ |
| NF-05 Backups, restore test | Local drill (`infra/backup/drill`); **drill in production-like conditions** | 🧑 [docs/ops/restore.md](../ops/restore.md) |
| NF-06 Availability, status page | Uptime Kuma, status page (ADR 0029) | 🧑 goes live with production |
| NF-07 Viewer < 2 s, validation < 5 s, load | `DocumentsIT`, `ValidationIT`, k6 | ✅ |
| NF-08 Scalability | Release 2 | – |
| NF-09 GDPR, lawyer review | Records and runbooks (Stage 9); **lawyer review** | 🧑 [lawyer brief](../legal/lawyer-review.md) |
| NF-10 Audit log, hash chain | `AuditChainIT`, daily chain check | ✅ |
| NF-11 WCAG 2.1 AA | axe in every Playwright test; keyboard test (`hardening.spec.ts`); **screen-reader check** | 🧑 [accessibility check](accessibility-manual.md) |
| NF-12 4 of 5 users understand an XRechnung | **usability test** | 🧑 [usability protocol](usability-nf12.md) |
| NF-13 German first, all texts in translation files | messages de/en; `RuleTextCatalogueTest` | ✅ |
| NF-14 Browsers, 375 px | Playwright desktop and 375 px projects | ✅ (Safari and Firefox: manual check in the accessibility protocol) |
| NF-15 Packs updated within 30 days | [validation-pack-update.md](../ops/validation-pack-update.md), release checklist | ✅ process |

## MVP acceptance checklist (Requirements §8)

| Item | Status |
|---|---|
| All Must requirements pass their acceptance criteria | 23 ✅ automatically. CR-05 (timed user test), EX-01 (advisor's DATEV import) and EX-03 (Stripe test clock) need people or external accounts |
| 100 % of the official XRechnung and ZUGFeRD test files parsed; results match KoSIT | ✅ `ConformanceIT` |
| Every generated invoice type validates without errors | ✅ `GeneratedInvoicesConformanceIT`: 3 formats × 6 VAT cases (incl. reverse charge, Kleinunternehmer) × invoice, credit note, correction |
| Restore test from backup succeeded | 🧑 second drill in production-like conditions ([restore.md](../ops/restore.md#drill-record)) |
| Lawyer has reviewed terms, AVV and privacy policy | 🧑 [lawyer brief](../legal/lawyer-review.md) |
| ASVS L1 self-assessment complete, no open high findings | ✅ [asvs-l1.md](../security/asvs-l1.md) |
