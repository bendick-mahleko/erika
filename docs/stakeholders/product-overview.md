# ERIKA – Product overview for management and product owners

**ERIKA** (*Elektronische Rechnungen im Kleinbetrieb abwickeln*) is an e-invoice inbox and creator for German micro and small businesses. This page explains what the product does, who it is for, where it stands and which decisions are open. Technical detail is linked, not repeated.

## 1. The problem

Since 1 January 2025, German businesses must be able to **receive** structured e-invoices (XRechnung, ZUGFeRD). From 2027/2028 they must also **send** them in B2B. Small businesses (trades, freelancers, small shops) often have no ERP system. They need a simple way to:

- receive e-invoices and actually read them (an XML file is not readable);
- know whether an invoice meets the rules;
- keep it as the law requires (GoBD, 8 years);
- write compliant e-invoices themselves;
- hand everything to their tax advisor.

## 2. Who uses it

| Persona | Need | ERIKA role |
|---|---|---|
| Owner of a small business | receive, check, pay and keep invoices; write own invoices | Owner |
| Office help / employee | daily handling, writing invoices | Member |
| Tax advisor | read, comment, export for bookkeeping, never change anything | Advisor |

## 3. What it does (status: implemented)

| Area | Capabilities | Requirement IDs |
|---|---|---|
| **Intake** | upload (single files, ZIP) and a personal email address; virus scan (fail closed); duplicate detection; sender allow-list with quarantine | IN-01 – IN-06 |
| **Checking** | detects format (XRechnung UBL/CII, ZUGFeRD/Factur-X, plain PDF); official KoSIT/EN 16931 validation plus PDF/A-3 check; plain-language German explanation for every rule; rule versions recorded | VW-03, VW-04, BR-07 |
| **Viewer** | every business term with a label, in German or English; PDF view copy; attachments; GiroCode for paying | VW-01 – VW-07, AR-06 |
| **Archive** | write-once storage (S3 Object Lock, compliance mode); retention per document; integrity check on every download | AR-01, BR-02 |
| **Workflow** | status, tags, notes, history; comments; search; reminders 3 days before due dates | AR-02 – AR-05 |
| **Creator** | customers and articles; drafts with legal checks; numbering without gaps; XRechnung CII/UBL and ZUGFeRD; credit notes and cancellations; sending by email with archived proof; logo; recurring drafts | CR-01 – CR-09 |
| **Team and export** | invitations and roles; period export with DATEV CSV; full account export; account closure | EX-01 – EX-04 |
| **Billing** | 14-day trial, Stripe subscription (card, SEPA), read-only instead of lock-out, ERIKA's own invoices as e-invoices | – (ADR 0033) |
| **Trust** | mandatory 2FA, tenant isolation tested on every build, hash-chained audit log, EU hosting (Hetzner, Germany), German-first UI, WCAG 2.1 AA target | NF-01 – NF-13 |

Delivered as Stages 1–12 of the build plan. The last is **Release 1.1** (allow-list, due-date reminders, GiroCode, logo, recurring invoices, English UI); see [ADR 0035](../adr/0035-release-1-1.md).

## 4. Where it stands

- **Software:** feature-complete for the MVP and Release 1.1, with automated tests (about 580 back-end tests, 60 browser tests including accessibility checks).
- **Closed beta:** invitation-only, billing off, target 20 beta users ([ops/beta.md](../ops/beta.md)).
- **Go-live gate:** open items that need people or money, not code ([ops/go-live.md](../ops/go-live.md)):
  - lawyer's review of the legal texts (all marked *draft*);
  - tax advisor's review of the procedure documentation (Verfahrensdokumentation);
  - data-processing agreements: Hetzner, outgoing mail provider (not chosen yet), Stripe;
  - usability test with 5 participants, and a manual accessibility check;
  - load test and restore drill on production-like infrastructure;
  - **pricing** (not decided) and Stripe live mode.

## 5. Decisions needed from you

| Decision | Why it matters | Blocks |
|---|---|---|
| **Price and plans** | Stripe products, landing page, terms | public launch |
| **Outgoing mail provider (EU)** | system emails and invoice sending; data-processing agreement | go-live |
| **Lawyer and tax advisor** for the reviews | legal texts may not stay drafts | go-live |
| **Peppol access point** (certified partner) | receiving via Peppol (IN-07) | Release 2 |
| **Public API pricing and abuse policy** | API keys and webhooks | Release 2 |
| **Partner model for tax advisors** (white-label) | EX-06 | Release 2 |
| **DATEV partner programme** | direct DATEV interface (EX-05) | Release 3 |

XRechnung 4.0 support is waiting for the official publication. The update process (new validation pack within 30 days) is in place ([ops/validation-pack-update.md](../ops/validation-pack-update.md)).

## 6. Roadmap

| Release | Content | Status |
|---|---|---|
| MVP | intake, checking, viewer, archive, workflow, creator, export, billing, hardening | done; go-live gate open |
| 1.1 | allow-list, reminders, GiroCode, logo, recurring invoices, English | done |
| — | XRechnung 4.0 | when published |
| 2 | Peppol, public API, tax-advisor white-label, Leitweg-ID/B2G; load test for 10,000 workspaces | needs decisions (above) |
| 3 | DATEV interface; invoice-core 1.0 shared with the trades app | needs DATEV partnership |

## 7. Risks to watch

| Risk | Mitigation in place |
|---|---|
| Cross-tenant data leak (fatal for trust) | database row-level security plus generated isolation tests on every build |
| New official rules (XRechnung/ZUGFeRD releases) | validation packs are data; update runbook; 30-day target |
| Mail deliverability | SPF/DKIM/DMARC; an EU provider still to choose |
| Solo developer unavailable | infrastructure as code, runbooks, automated deploys and restore drills |
| DATEV import not accepted by the advisor's software | early test with a pilot advisor during the beta |

## Where to read more

- Build plan and traceability: `docs/BUILD_PLAN.md` (internal), [testing/traceability.md](../testing/traceability.md)
- Architecture decisions: [adr/README.md](../adr/README.md)
- Other stakeholder guides: [README.md](README.md)
