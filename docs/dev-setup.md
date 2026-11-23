# Developer set-up

## Tools

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle toolchain; `JAVA_HOME` must point to a JDK 21 |
| Node.js | 24 | |
| pnpm | 12 (pinned via `packageManager` in `frontend/package.json`) | `corepack enable` |
| Docker | recent Docker Desktop / Engine | needed for local infra and for integration tests (Testcontainers) |

## Local infrastructure

```bash
docker compose -f infra/local/compose.yml up -d        # postgres, s3, s3-init, clamav, mailpit
docker compose -f infra/local/compose.yml down -v      # reset everything (deletes data)
```

| Service | Address | Credentials (dev only) |
|---|---|---|
| PostgreSQL 16 | `localhost:5432/erika` | app `erika_app` / `erika_app_dev`; owner `erika_owner` / `erika_owner_dev`; superuser `postgres` / `postgres_dev` |
| S3 (SeaweedFS) | `http://localhost:9000`, buckets `erika-originals` and `erika-backups` (Object Lock on; the app key may set retention but not delete) and `erika-uploads` (upload quarantine, CORS for the browser) | app key `erika-dev` / `erika-dev-secret`; admin `seaweed_admin` / `seaweed_admin_dev` |
| Mailpit | SMTP `localhost:1025`, UI http://localhost:8025 | – (all mail sent by the worker lands here) |
| ClamAV | `localhost:3310` (clamd) | – (loads its signatures for about a minute after start) |
| Intake mail (profile `app`) | SMTP `localhost:2525`, domain `in.erika.local` | shared secret `dev-intake-secret` with the API |

Database roles: the app runs as `erika_app` (no DDL, no BYPASSRLS); Flyway migrates as `erika_owner` (ADR 0011).

## Back end

```bash
cd backend
./gradlew check                  # ktlint (Spotless) + unit + integration + modularity tests
./gradlew spotlessApply          # format
./gradlew bootRun --args='--spring.profiles.active=api,local'
./gradlew bootRun --args='--spring.profiles.active=worker,local'
```

- Exactly one of the profiles `api` or `worker` must be active; add `local` for development defaults.
- The API runs Flyway migrations at start; start it before the worker.
- Ports: API 8080, actuator (health, Prometheus) 8081 — never routed publicly.
- Generated code: `./gradlew openApiGenerate` writes the contract interfaces to `build/generated/openapi`.
- Module docs: `./gradlew test` writes diagrams to `build/spring-modulith-docs`.

### Adding a background job

1. Implement `JobHandler<P>` as a Spring bean with a unique `type` (e.g. `intake.parse-and-validate`). Make it idempotent.
2. Enqueue with `JobQueue.enqueue(type, payload, workspaceId)` inside the transaction that creates the data the job needs.
3. Test it with `WorkerIntegrationTest` and `JobWorker.runOnce()` (see `JobWorkerIT`).

### Signing in locally

1. Start the API **and** the worker (the worker sends mail), then open http://localhost:3000/signup.
2. The verification email arrives in Mailpit (http://localhost:8025); open its link.
3. Sign in. Set up an authenticator app by scanning the QR code (2FA is mandatory for every role, ADR 0017).
4. Locked yourself out with 5 wrong attempts? Wait 15 minutes, or
   `UPDATE erika.app_user SET locked_until = NULL WHERE email = '…';` as `erika_owner`.

The local profile uses a fixed dev key for TOTP secrets (`ERIKA_TOTP_ENCRYPTION_KEY`) and non-secure cookies, because it runs over http.

### Tenancy, roles and audit in new code

- Signed-in requests already run in a tenant transaction (ADR 0018). Code outside a request (jobs, sign-in) calls `TenantContext.enterWorkspace(id)` inside its transaction.
- A new tenant table needs `workspace_id NOT NULL`, an index, `ENABLE ROW LEVEL SECURITY` and a policy using `current_workspace_id()`. `TenancyDatabaseIT` fails otherwise.
- A new endpoint is picked up by `TenantIsolationIT` from the OpenAPI file. It needs an `example` for its request body and, for new path parameters, a tenant-B fixture.
- Record auditable changes by publishing an `AuditableEvent` in the changing transaction.

### Archive and validation

- `./gradlew prepareValidationPacks` assembles the validation packs into `build/validation-packs` (run automatically by `test` and `bootRun`). Downloads are cached in `~/.gradle/caches/erika-downloads`.
- Validation runs in the worker only (`InvoiceValidator`); start-up compiles the active pack (a few seconds).
- New German rule texts: see `validation-packs/README.md`. `RuleTextCatalogueTest` fails if a rule ID has no text.
- Originals go through `Archive.store/open` inside a workspace-scoped transaction; never write to the originals bucket directly. The app key has no delete permission, and versions are locked (Object Lock, COMPLIANCE).
- invoice-core is built from source as part of the back-end build (composite build, ADR 0020). Change its API in `invoice-core/` and the back end sees it immediately.

### Intake (uploads and email)

- Upload: the browser gets a pre-signed URL from `POST /uploads`, sends the file straight to the `erika-uploads` bucket, then calls `/complete`; the worker scans (ClamAV), unpacks ZIPs, archives and validates (ADR 0023). With the back end on the host, `ERIKA_S3_PUBLIC_ENDPOINT` defaults to `http://localhost:9000` (profile `local`).
- Email: with the `app` profile, send a mail to your workspace's address (Eingang page) through `localhost:2525`, e.g. `swaks --server localhost:2525 --to <token>@in.erika.local --attach @frontend/e2e/fixtures/xrechnung-beispiel.xml`. Without Postfix, post a raw message to `POST /internal/intake/mail` with `X-Intake-Secret: dev-intake-secret` (`api/internal.yaml`).
- The worker needs ClamAV (`ERIKA_CLAMAV_HOST`); without it intake jobs fail and retry, never pass unscanned.
- Integration tests: `IntakeIT` drives both roles: API requests in the test's context, background jobs in a worker context in the same JVM (`support/WorkerContext.kt`, `drain()`).
- EICAR test files are assembled at run time (`Samples.eicar()`, `e2e/support.ts`); don't commit the EICAR string, local virus scanners would quarantine the file.

### Documents, viewer and search

- The worker builds the invoice view (`parsed_invoice.view`), the PDF view copy and decoded attachments (bucket `erika-derived`) when it reads a document (ADR 0025); the API only serves them. `POST /documents/{id}/revalidate` rebuilds them.
- Search uses PostgreSQL full text (`german`) and `pg_trgm` (ADR 0026). The extension needs a superuser. Fresh databases get it from `infra/local/postgres/01-roles.sql`. For a database created before Stage 6, run once:
  `docker compose -f infra/local/compose.yml exec postgres psql -U postgres -d erika -c "CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA erika;"`
  Migration V5 stops with this hint if it's missing.
- After pulling Stage 6, restart S3 so it reads the new bucket permissions: `docker compose -f infra/local/compose.yml restart s3`, then `up -d` (s3-init creates `erika-derived`).

### Closed beta, feedback and the worker's metrics (Stage 7)

- Sign-up is `OPEN` locally (`ERIKA_SIGNUP_MODE`); staging and production use `INVITE_ONLY` (ADR 0030). To try invitations locally, start the API with `ERIKA_SIGNUP_MODE=INVITE_ONLY ERIKA_INVITE_SECRET=<32+ characters>` and create a link with `ERIKA_INVITE_SECRET=… ERIKA_DOMAIN=localhost:3000 python infra/production/invite.py you@example.test` (change `https` to `http` in the printed link).
- Sign-up needs `termsAccepted: true`; API tests send it via `Accounts.registered`.
- In-app feedback is mailed to `ERIKA_FEEDBACK_TO` (`beta@erika.local` in the local stack, so it shows up in Mailpit).
- The worker serves Prometheus metrics on port 8082 (`/metrics`); tests use a free port (`erika.worker.metrics-port=0`).

## Operations (Stage 7)

- Production layout, setup and routine work: [docs/ops/production.md](ops/production.md); backups and restore drills: [backup.md](ops/backup.md), [restore.md](ops/restore.md); alerts: [monitoring.md](ops/monitoring.md); beta: [beta.md](ops/beta.md).
- Local restore drill (backup → change → restore → point-in-time restore), needs the local stack's `postgres`: `infra/backup/drill/run-local-drill.sh` (about a minute; leaves nothing behind).
- Alert rule tests: `docker run --rm -v "$PWD/infra/monitoring:/m" -w /m --entrypoint promtool prom/prometheus:v3.5.0 test rules tests/alerts.test.yml`. CI job `infra` also validates Terraform, Ansible, Caddyfiles, Loki, Alloy and the shell scripts.

## Front end

```bash
cd frontend
pnpm install
pnpm dev               # regenerates the API client, then starts Next.js on :3000
pnpm lint && pnpm typecheck && pnpm format:check
pnpm build && pnpm test:e2e      # Playwright (first time: pnpm exec playwright install chromium)
```

`ERIKA_API_INTERNAL_URL` (default `http://localhost:8080`) tells server components where the API is. Browser calls go to `/api/v1/*` on the web app's own origin; `src/proxy.ts` forwards them to that URL, so the session cookie stays first-party (Caddy does this in staging).

Full-stack E2E tests (`E2E_EXPECT_API=true`) read the verification mail from Mailpit: set `E2E_MAILPIT_URL` if its UI isn't on `http://localhost:8025`. The intake tests send email through the mail container: `E2E_SMTP_HOST` / `E2E_SMTP_PORT` (default `localhost:2525`).

Texts: every UI string goes into `messages/de.json` and `messages/en.json` (NF-13).

## VS Code

The Java extension imports the Gradle project and may compile into `backend/build` at the same time as a terminal build, which shows up as errors like "Could not delete …/build/classes" or "Incremental compilation failed". Re-run the build. If it keeps happening, let one tool build at a time.

`backend/gradle.properties` turns off the Kotlin plugin's usage statistics: with a large `~/.gradle/kotlin-profile` directory they stall every build for minutes at the end.

## Contract-first workflow

1. Edit `api/openapi.yaml`; lint with `npx @redocly/cli lint --config api/redocly.yaml api/openapi.yaml`.
2. Back end: implement the regenerated interface (compile errors show what's missing).
3. Front end: `pnpm gen:api` updates the typed client.
