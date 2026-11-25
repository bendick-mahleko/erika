# ERIKA – Compliance overview for data protection, legal and audit

This page is for the **data protection officer**, the **lawyer** reviewing the legal texts, the **tax advisor** reviewing the procedure documentation, and **auditors** (including tax auditors). It summarises how ERIKA meets its obligations and links to the evidence. Everything in `docs/legal/` and `frontend/src/content/legal/` is a **draft by the development team, not legal advice**, until it has been reviewed.

## 1. Roles under the GDPR

| Data | ERIKA is | Basis |
|---|---|---|
| Customers' invoices and the business partners named in them | **processor** (Art. 28 GDPR) | data-processing agreement (`/auftragsverarbeitung`) |
| Users' account data (name, email, sign-in and 2FA data, audit entries about them) | **controller** | contract (Art. 6(1)(b)); privacy notice (`/datenschutz`) |

Record of processing activities: [legal/verarbeitungsverzeichnis.md](../legal/verarbeitungsverzeichnis.md). How requests from data subjects are handled: [ops/gdpr.md](../ops/gdpr.md).

## 2. Sub-processors and data location

| Sub-processor | Purpose | Location | Status |
|---|---|---|---|
| Hetzner Online GmbH | servers, volumes, object storage | Nuremberg and Falkenstein, Germany | data-processing agreement to conclude |
| Outgoing mail provider | system emails, sending invoices | EU (not chosen yet) | open |
| Stripe Payments Europe Ltd. | subscriptions (card, SEPA) | Ireland; possible US transfers under standard contractual clauses | before billing goes live |

Monitoring, logs and error tracking run on ERIKA's own servers. The external heartbeat and push services receive only alert and host names. Details and status: [legal/README.md](../legal/README.md).

## 3. GoBD and tax law

| Obligation | How ERIKA meets it | Evidence |
|---|---|---|
| **Immutability** of originals | stored write-once (S3 Object Lock, COMPLIANCE mode; the app's key cannot delete); SHA-256 checked on every download; regular integrity checks | [ADR 0022](../adr/0022-archive-write-once-originals.md), [ops/archive-integrity.md](../ops/archive-integrity.md) |
| **Retention** (§ 147 AO, § 14b UStG as amended by BEG IV) | 8 years from the end of the calendar year of issue (or receipt, if no issue date); 10 years for invoices dated before 2025; stored per document | `RetentionPolicy`, ADR 0022 |
| **Traceability** | hash-chained audit log for every change, export, role change and payment status; checked nightly | [ADR 0019](../adr/0019-audit-hash-chain.md), [ops/audit-chain.md](../ops/audit-chain.md) |
| **Completeness of intake** | the full email is archived before its attachments are processed; rejected files (e.g. malware) appear in the intake log | Verfahrensdokumentation § 2.1 |
| **Correct outgoing invoices** (§ 14 Abs. 4 UStG) | required fields checked before issuing; numbering without gaps; corrections only by credit note, cancellation or corrected invoice; the sent email is archived with the file | [ADR 0031](../adr/0031-invoice-creator.md) |
| **Validation with known rules** | official KoSIT configuration and EN 16931 Schematron, PDF/A-3 check; the rule version is stored with every result; rule updates within 30 days of a new official release | [ADR 0021](../adr/0021-validation-packs-and-engine.md), [ops/validation-pack-update.md](../ops/validation-pack-update.md) |
| **Data access by tax authorities** (§ 147 Abs. 6 AO) | full account export: originals, data as JSON, audit log with hash chain, `MANIFEST.sha256` | Verfahrensdokumentation § 2.6 |
| **Procedure documentation** | written in German | [legal/verfahrensdokumentation.md](../legal/verfahrensdokumentation.md); review: [legal/tax-advisor-review.md](../legal/tax-advisor-review.md) |

A workspace that is closed, or read-only because of non-payment, keeps all originals until their retention ends. Reading and export stay possible.

## 4. Security (technical and organisational measures)

- **Access control:**
  - mandatory second factor (TOTP) for every role;
  - sign-in limited after 5 failed attempts;
  - new passwords checked against known breaches (k-anonymity; only a hash prefix leaves the system);
  - roles Owner, Member and Advisor, enforced on the server; tax advisors can never change data.
- **Tenant isolation:**
  - PostgreSQL row-level security on every customer table;
  - the application role cannot bypass it;
  - automated tests on every build try every endpoint with another tenant's IDs.
- **Malicious files:** every incoming file is virus-scanned before it is stored (fail closed); ZIP bombs are rejected; the API never parses files, only the isolated worker does.
- **Transport and storage:**
  - TLS everywhere, including to the database;
  - encrypted database volume;
  - encrypted backups at a second location with Object Lock.
- **Web security:** strict Content Security Policy with nonces; CSRF protection; secure cookies; OWASP ZAP baseline and container vulnerability scans in CI.
- **Backups:** continuous WAL archiving (data loss at most about 5 minutes), nightly full backups kept 35 days, automatic monthly restore drill; restore target 4 hours.
- **Logging:** no invoice content in logs or error messages; logs on own servers.
- **Self-assessment:** OWASP ASVS Level 1, [security/asvs-l1.md](../security/asvs-l1.md).

## 5. Legal texts

The German legal texts are content modules in `frontend/src/content/legal/` and are shown in the app:

| Page | Content |
|---|---|
| `/impressum` | imprint |
| `/datenschutz` | privacy notice |
| `/nutzungsbedingungen` | terms (beta version; versioned, acceptance stored per user) |
| `/auftragsverarbeitung` | data-processing agreement |
| `/unterauftragsverarbeiter` | list of sub-processors |

All are marked `draft: true` until a lawyer has reviewed them. Review checklist: [legal/lawyer-review.md](../legal/lawyer-review.md). A new terms version requires users to accept it again.

## 6. Accessibility

Target: WCAG 2.1 AA. Automated axe checks run in the browser tests. Manual check and usability test: [testing/accessibility-manual.md](../testing/accessibility-manual.md), [testing/usability-nf12.md](../testing/usability-nf12.md).

## 7. Open items before go-live

From [ops/go-live.md](../ops/go-live.md):
- lawyer's review of all legal texts; fill in the placeholders;
- tax advisor's review of the procedure documentation;
- data-processing agreements with Hetzner, the mail provider and Stripe; Stripe added to the privacy notice and sub-processor list before billing starts;
- ASVS L1 without open high findings;
- manual accessibility check without open blockers.
