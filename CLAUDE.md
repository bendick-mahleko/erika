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
- Every tenant table: `workspace_id NOT NULL`, index, RLS policy. The app role is `erika_app` (no DDL); migrations run as `erika_owner`. Flyway migrations are expand-then-contract only.
- Jobs: `JobHandler` implementations must be idempotent; enqueue inside the creating transaction.
- Errors: RFC 9457 problem details with a stable `code` (`ErikaException`).
- No invoice content in logs or exception messages.
- UI texts in `frontend/messages/{de,en}.json`; German first.
- Spring Boot 4 / Jackson 3 (`tools.jackson.*`), not Jackson 2.
