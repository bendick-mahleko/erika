# 0036. MARA (job reports for trades) as modules of ERIKA

- Status: Accepted
- Date: 2026-11-25
- Supersedes: MARA software design decisions M7 and M8 and ADR M-007 ("separate app on shared infrastructure")

## Context
MARA (*Mobile Arbeitsberichte, Rechnungen und Aufmaße*) lets technicians of small trades firms record jobs on site, offline, and lets the office turn signed reports into e-invoices. Its software design (21 Nov 2026) planned a separate back end (`mara-api`, own database and bucket) that calls ERIKA over an internal mTLS API for invoicing.

MARA's users are ERIKA's core persona, and MARA needs most of what ERIKA already has: customers and articles, team and roles, 2FA sign-in, tenancy with row-level security, the audit chain, the write-once archive, the job queue, mail, export, billing and the invoice creator. A separate back end would copy or sync all of that, which is the most expensive part of the system for a solo developer. On 25 Nov 2026 the owner decided: **MARA is built as modules of ERIKA's back end, with a separate mobile front end, and sold as an add-on.** The build plan is [docs/mara/BUILD_PLAN.md](../mara/BUILD_PLAN.md).

## Decision

### Where the code lives
- **Back end:** new Spring Modulith modules in `backend/`: `workorders`, `reports`, `media`, `sync` and `reportinvoicing`. Existing modules are extended where MARA touches their data: `creator` (customer type, Leitweg-ID, purchase price, draft attachments, source reference, § 35a labour share), `identity` (TECHNICIAN role, devices) and `billing` (add-on item, seats).
  - Same artefact, same `api` and `worker` profiles, same database (schema `erika`) and buckets (own key prefixes).
  - Kotlin has no `package-info`: each new module declares itself with a class annotated `@PackageInfo @ApplicationModule(displayName = …)` (`ModuleInfo.kt`). `ModularityTest` checks that all five are found.
- **Office web:** a new area in `frontend/` (Next.js), behind the add-on entitlement.
- **Mobile app:** Flutter in `mobile/` (MARA design M1: Riverpod, drift with SQLCipher). German and English ARB files from the first screen.
- **Shared rules:** `mara-rules/` holds JSON test vectors for every rule that runs in both Kotlin and Dart (Aufmaß, canonical report hash, invoice lines, sync cases). Both test suites read the same files. Numbers are decimal strings.

### How MARA uses ERIKA
- **Tenant:** a MARA firm *is* an ERIKA workspace. Every new table has `workspace_id NOT NULL`, an index and an RLS policy (`TenancyDatabaseIT`).
- **Invoicing:** in-process. `reportinvoicing` creates drafts through `creator`'s published interface; invoices are issued only by `creator.InvoiceIssuer` in the worker. Status comes back as application events. There is no internal HTTP API, no mTLS and no workspace provisioning.
- **Customers and articles:** ERIKA's (CR-05), extended expand-only. There is one customer list for invoices and work orders.
- **Roles:** OWNER and MEMBER as today (MEMBER does the office work), plus TECHNICIAN (ADR 0039). ADVISOR stays read-only.
- **Contracts:** office endpoints go in `api/openapi.yaml`, so the existing generated harnesses cover them. Mobile and sync endpoints go in **`api/mobile.yaml`** under `/api/v1/mobile`, with bearer tokens (ADR 0037).
  - It is generated into `de.erika.contract.mobile` (Gradle task `openApiGenerateMobile`) and linted in CI.
  - `MobileContractIT` reads it: an operation without a token must be on its allow-list. Every token operation fails until the mobile isolation harness has a fixture for it (Stage 3).
- **Entitlement:** MARA endpoints need the add-on (`EntitlementInterceptor`, Stage 10).

### Naming
- **Work orders.** "Job" already means a background job in ERIKA (`job` table, `JobHandler`). MARA's jobs are `work_order` in code, tables and the API, and *Auftrag* in the German UI.
- **Requirement IDs.** MARA's IDs collide with ERIKA's (NF-xx, BR-xx), so they carry the prefix `M-` in code, tests and traceability (M-MO-05, M-NF-02).

### Build and CI
- **CI jobs.** `mobile` runs format, analyze, tests and a debug Android build in the pinned `ghcr.io/cirruslabs/flutter:3.44.0` image, the same image used locally (`mobile/README.md`). `mobile-ios` builds without signing on macOS runners, on pushes to main only (spike S1-b): macOS minutes cost several times Linux minutes. Codemagic stays the fallback if the iOS minutes get too expensive once signing and TestFlight uploads are added (Stage 9).
- **SQLCipher.** `package:sqlite3` 3.x bundles SQLCipher through build hooks (`hooks.user_defines.sqlite3.source: sqlcipher` in `pubspec.yaml`). The old `sqlcipher_flutter_libs` package is obsolete with sqlite3 3.x and is not used. The app refuses to open a database without SQLCipher (`PRAGMA cipher_version`).

## Consequences
- MARA reuses tenancy, sign-in, audit, archive, jobs, mail, export and billing without copies, and ERIKA's rules (CLAUDE.md) apply to it unchanged.
- MARA and ERIKA share one release train: a MARA back-end change ships with an ERIKA release. App-store delays are absorbed by the sync protocol's two supported versions (ADR 0038).
- A defect in a MARA module can affect ERIKA. Module boundaries, the generated harnesses and the add-on entitlement limit this; MARA's endpoints are separate paths.
- Spike S1-a (SQLCipher start-up time on the low-end reference phone) needs a real device. The Stage 1 status screen shows the database's opening time for that measurement. Spike S1-c (trademark "MARA") is an owner action. Both are open.
