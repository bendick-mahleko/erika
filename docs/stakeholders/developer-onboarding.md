# ERIKA – Onboarding for developers

Welcome. This page is the map: what the system looks like, the rules that keep it safe, and where the detail lives. Read it once, then use [dev-setup.md](../dev-setup.md) as your daily reference.

## 1. Get it running

Follow [running-locally.md](../running-locally.md): Docker for the infrastructure, then API, worker and web app. You need JDK 21, Node 24 with pnpm, and Docker.

## 2. The system in one picture

```
Browser ──► web (Next.js 16, next-intl) ──/api/v1──► API (Spring Boot 4, Kotlin)
                                                     │  tenant transaction per request
                                                     ▼
                         PostgreSQL 16 (RLS) ◄── job table ──► worker (same code, profile "worker")
                                                     │
               S3 (originals write-once, uploads, derived, exports)   ClamAV   SMTP
```

- **One back-end codebase, two roles.** The `api` profile serves requests; the `worker` profile runs jobs from the `job` table. Heavy or risky work runs only in the worker: parsing files, validating, rendering, sending email, calling Stripe.
- **Modular monolith** (Spring Modulith): `identity`, `intake`, `documents`, `archive`, `validation`, `creator`, `export`, `billing`, `audit`, `feedback`, `platform`, and for the MARA job-report add-on `workorders`, `reports`, `media`, `sync`, `reportinvoicing` (ADR 0036; the Flutter app is in `mobile/`). Modules talk through published interfaces or events, never through another module's tables. `ModularityTest` enforces this.
- **invoice-core** (`invoice-core/`) is a separate library for parsing, the view, writing and calculation. It is built from source as a composite build. Its version must match `backend/gradle/libs.versions.toml`.
- **Contract first:** `api/openapi.yaml` generates the Kotlin server interfaces and the TypeScript client. Change the contract before the code; never edit generated code.

## 3. Rules that are not optional

The full list is in [CLAUDE.md](../../CLAUDE.md). The ones that bite most often:

1. **Tenancy.** Every tenant table has `workspace_id NOT NULL`, an index and an RLS policy using `current_workspace_id()`. Never take a workspace ID from the request. Jobs call `TenantContext.enterWorkspace` inside their transaction.
2. **Tests that guard this are generated from the contract:**
   - `TenantIsolationIT`: every endpoint, as tenant A, using tenant B's IDs. A new path parameter needs a tenant-B fixture, and every JSON request body needs an `example`.
   - `AdvisorReadOnlyIT`: every write endpoint as a tax advisor must answer 403.
   - `TenancyDatabaseIT`: every table has RLS.
3. **Originals** go only through `archive.Archive` (write-once). **Validation** goes only through `validation.InvoiceValidator`, in the worker. **Status changes** go only through `documents.Documents.changeStatus`. **Invoices are issued** only by `creator.InvoiceIssuer`.
4. **Audit:** auditable changes publish an `AuditableEvent` inside the changing transaction.
5. **Jobs are idempotent** and are enqueued inside the transaction that creates their data.
6. **POSTs that create or send** take an `Idempotency-Key`; editable resources use ETag/If-Match.
7. **Migrations only expand** (Flyway, run as `erika_owner`; the app runs as `erika_app` without DDL).
8. **No invoice content in logs or exception messages.** Errors are RFC 9457 problem details with a stable `code`.
9. **UI texts** go in `frontend/messages/de.json` and `en.json`. German first, the same keys in both (`pnpm lint` checks this). Forms use `method="post"`.
10. **Jackson 3** (`tools.jackson.*`), not Jackson 2.

## 4. Everyday commands

```bash
cd backend      && ./gradlew check            # format + all tests (Docker needed)
cd backend      && ./gradlew spotlessApply    # format Kotlin
cd invoice-core && ./gradlew check
cd frontend     && pnpm lint && pnpm typecheck && pnpm format
cd frontend     && pnpm test:e2e              # Playwright, see running-locally.md
```

Windows tip: when Gradle reports missing or locked generated classes, run `./gradlew --stop`, then delete `backend/build/kotlin` and `backend/build/classes/kotlin`, and build again.

## 5. Adding a feature: the usual path

1. Read the related ADR(s) in [docs/adr/](../adr/README.md). Write a new ADR for a real decision.
2. Change `api/openapi.yaml`: add an `example` for request bodies and tenant-B fixtures for new path parameters.
3. Add a Flyway migration (new tables with RLS) and seed the table in `TenancyDatabaseIT`.
4. Implement in the owning module; publish audit events; use the worker for heavy work.
5. Write tests: unit tests, and an `ApiIntegrationTest` that drains the worker (`WorkerContext.drain()`).
6. Front end: pages in `frontend/src/app/`, typed client from `@/lib/api/browser` or `client`, texts in both message files, and a Playwright test with `expectAccessible`.
7. Update docs: `dev-setup.md`, a runbook if operators are affected, and `CLAUDE.md` for new rules.

## 6. Where things are

| Need | Look at |
|---|---|
| Decisions and their reasons | [docs/adr/](../adr/README.md), [invoice-core/docs/adr/](../../invoice-core/docs/adr/) |
| Developer notes per area | [dev-setup.md](../dev-setup.md) |
| Requirements → tests | [testing/traceability.md](../testing/traceability.md) |
| Security self-assessment | [security/asvs-l1.md](../security/asvs-l1.md) |
| Operations | [operations-and-support.md](operations-and-support.md), [docs/ops/](../ops/) |
| Validation packs | `validation-packs/README.md` ([ADR 0021](../adr/0021-validation-packs-and-engine.md)) |
