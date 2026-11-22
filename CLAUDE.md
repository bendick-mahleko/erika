# ERIKA – notes for Claude Code

Plan: `docs/BUILD_PLAN.md` (stages, exit criteria, traceability). Decisions: `docs/adr/`. Setup: `docs/dev-setup.md`.

## Commands
- Back end (from `backend/`): `./gradlew check` (format + all tests; needs Docker), `./gradlew spotlessApply`, `./gradlew test --tests '*JobWorkerIT'`.
- Front end (from `frontend/`): `pnpm lint`, `pnpm typecheck`, `pnpm format`, `pnpm build`, `pnpm test:e2e`.
- invoice-core (from `invoice-core/`): `./gradlew check`, `./gradlew publishToMavenLocal`; own rules in `invoice-core/CLAUDE.md`.
- Local stack: `docker compose -f infra/local/compose.yml [--profile app] up -d [--build]`.

## Rules
- Contract first: change `api/openapi.yaml` before code; never edit generated code (`backend/build/generated`, `frontend/src/lib/api/schema.d.ts`).
- Module boundaries (Spring Modulith) are enforced by `ModularityTest`; feature modules talk via published interfaces or events, never another module's tables.
- Every tenant table: `workspace_id NOT NULL`, index, RLS policy using `current_workspace_id()` (checked by `TenancyDatabaseIT`; system tables are listed there with a reason). The app role is `erika_app` (no DDL); migrations run as `erika_owner`. Flyway migrations are expand-then-contract only.
- Signed-in requests run in one transaction scoped by `TenantTransactionFilter`; elsewhere use `TenantContext.enterWorkspace` inside a transaction. Never take a workspace ID from the request.
- New endpoints: request bodies need an `example`, and new path parameters need a tenant-B fixture in `TenantIsolationIT` (NF-03 harness, generated from the OpenAPI file).
- Auditable changes publish an event implementing `platform.audit.AuditableEvent` inside the changing transaction (hash-chained `audit_log`, ADR 0019).
- Role checks: `@PreAuthorize` per endpoint; tax advisors (ADVISOR) never change data.
- Originals only through `archive.Archive` (write-once, Object Lock, hash check on open). Validation only through `validation.InvoiceValidator` (worker only).
- Validation packs are data (`validation-packs/`, ADR 0021): never commit third-party artefacts, pin them by SHA-256 in `pack.json`; every rule ID needs a German text (`RuleTextCatalogueTest`).
- invoice-core is built from source (composite build, ADR 0020); keep `invoice-core/gradle.properties` and the version in `backend/gradle/libs.versions.toml` equal.
- Jobs: `JobHandler` implementations must be idempotent; enqueue inside the creating transaction.
- Intake (ADR 0023/0024): every received file is virus-scanned (fail closed) before `archive.Archive.store`; documents are created only via `documents.Documents.registerIncoming`. `/internal/intake/**` is for the mail server only (shared secret, `api/internal.yaml`).
- Never commit the EICAR test string; tests assemble it at run time.
- Errors: RFC 9457 problem details with a stable `code` (`ErikaException`).
- No invoice content in logs or exception messages.
- UI texts in `frontend/messages/{de,en}.json`; German first.
- Spring Boot 4 / Jackson 3 (`tools.jackson.*`), not Jackson 2.
