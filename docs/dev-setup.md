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

All host ports can be moved with `ERIKA_*_PORT` variables when another project already uses them; the back end reads `ERIKA_SMTP_PORT` and `ERIKA_CLAMAV_PORT` too. See [running-locally.md, section 3](running-locally.md#3-check-that-the-ports-are-free).

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

1. Start the API, the worker (it sends the mail) **and** the web app (`cd frontend; pnpm dev`), then open http://localhost:3000/signup. Step by step: [running-locally.md, Option B](running-locally.md#option-b--developer-mode).
2. The verification email arrives in Mailpit (http://localhost:8025, or the port in `ERIKA_MAILPIT_UI_PORT`); open its link. If another project's Mailpit holds port 1025 and you didn't move ERIKA's ports, the mail lands in *that* Mailpit.
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

### Invoice creator (Stage 8)

- Before the first invoice, fill in the company details (`/settings/workspace`: address and VAT ID or tax number) and the invoice settings (`/settings/invoices`: IBAN; for XRechnung also contact name, phone and email). The draft lists what is missing.
- Issuing and sending run in the **worker** (ADR 0031): with only the API running, a draft stays in "Wird ausgestellt …". Locally the `worker` service of the app profile does it; in API tests call `WorkerContext.drain()` (helpers in `support/CreatorSupport.kt`).
- Sent invoice emails land in Mailpit; the archived `.eml` can be downloaded from the invoice page.
- Delivery webhook: `POST /webhooks/mail` with `X-Erika-Webhook-Secret: $ERIKA_MAIL_WEBHOOK_SECRET` (empty = off; tests use `test-webhook-secret`), body `{"messageId": "<…>", "event": "BOUNCED"}`.
- invoice-core 0.4 writes the files (invoice-core ADR 0006). After changing a serialiser run invoice-core's `./gradlew check` and the back end's `./gradlew test --tests '*GeneratedInvoicesConformanceIT'` (KoSIT + veraPDF on every format and VAT case).

### Team, export and account closure (Stage 9)

- Invitations: the email contains `/invitations/accept?token=…` (Mailpit locally). New users sign up from there without a workspace of their own; existing users accept while signed in. Users in several workspaces switch in the header (`POST /auth/workspace`). In API tests, `accounts.invited(ownerClient, "ADVISOR")` runs the whole flow.
- Role checks: every write endpoint is closed to ADVISOR in `SecurityConfiguration` except feedback, comments and `POST /exports`; `AdvisorReadOnlyIT` sends every write operation of the contract as ADVISOR and expects 403. A new write endpoint for advisors needs an entry with a reason there (ADR 0032).
- Exports are built by the worker (`export.build`) into the `erika-exports` bucket (s3-init creates it; after pulling Stage 9 run `docker compose -f infra/local/compose.yml restart s3` and `up -d`). The download is a 303 to a pre-signed URL, so the browser must reach S3 (`ERIKA_S3_PUBLIC_ENDPOINT`).
- DATEV: enter consultant and client number in `/settings/datev`; without them the ZIP has no `EXTF_Buchungsstapel` file. `DatevCsvTest` covers the format.
- Full export: modules contribute their data as `platform.export.AccountDataSection` beans; a new module with its own tables adds one.
- A closed workspace (`POST /workspace/closure`) is read-only (`WorkspaceAccessInterceptor`); there is no reopening in the app. Locally: `UPDATE erika.workspace ...` is refused by the trigger too; use a fresh account.

### Billing (Stage 10)

- Billing is off locally (`ERIKA_BILLING_ENABLED=false`): no trial limits; `/settings/billing` says so. Tests run with billing on against `support/FakeStripe` (`application-test.yml`); `BillingIT` signs webhook payloads with `StripeSignature("whsec_test_erika")`.
- Trying it with Stripe test mode: set `ERIKA_BILLING_ENABLED=true`, `ERIKA_STRIPE_SECRET_KEY=sk_test_…`, the price and tax-rate IDs, and `ERIKA_BILLING_ISSUER_WORKSPACE` (a local workspace with complete company data), then forward webhooks with `stripe listen --forward-to localhost:8080/webhooks/stripe` and put the printed `whsec_…` into `ERIKA_STRIPE_WEBHOOK_SECRET`.
- Entitlement: a READ_ONLY workspace gets 403 `SUBSCRIPTION_REQUIRED` on writes (`EntitlementInterceptor`); exports, feedback, `/auth` and `/billing` stay open. To see it locally: `UPDATE erika.billing_account SET trial_ends_at = now() - interval '1 day' WHERE workspace_id = '…';` (with billing on).
- ERIKA's own invoices go through `creator.SystemInvoices` (issued in the worker, emailed via `invoice_draft.send_to`); the runbook is [docs/ops/billing.md](ops/billing.md).

### Hardening (Stage 11)

- Passwords: reset by email (`/login/forgot`, link in Mailpit) and change in `/settings/account`. The breach check calls the Pwned Passwords range API; switch it off offline with `ERIKA_PASSWORD_BREACH_CHECK=false` (tests do).
- The web app sends a nonce-based CSP (`frontend/src/proxy.ts`). If the browser must talk to another origin (e.g. S3 in another place), add it to `ERIKA_CSP_CONNECT_SRC`. `e2e/hardening.spec.ts` fails on CSP violations.
- Security scans as in CI, against the running stack:
  - ZAP: `docker run --rm -v "$PWD/.zap:/zap/wrk" ghcr.io/zaproxy/zaproxy:stable zap-baseline.py -t http://host.docker.internal:3000 -I -c rules.tsv`
  - Trivy: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.67.2 image --severity HIGH,CRITICAL --ignore-unfixed erika-frontend:local`
- Load tests: [loadtest/README.md](../loadtest/README.md). Security self-assessment: [docs/security/asvs-l1.md](security/asvs-l1.md). Traceability and go-live: [docs/testing/traceability.md](testing/traceability.md), [docs/ops/go-live.md](ops/go-live.md).

### Release 1.1 (Stage 12, ADR 0035)
- Sender allow-list: Settings → E-Mail-Eingang. Mail from the local SMTP port comes from `rechnung@lieferant.example`; with the list on and that sender missing, it lands in the quarantine.
- Daily jobs: due reminders (`erika.documents.due-reminder-cron`, default 07:00) and recurring invoices (`erika.creator.recurring-cron`, default 05:30), both Europe/Berlin, worker only. Tests enqueue them with a fixed day (`DueReminderJobHandler.Payload(dueDate)`, `RecurringInvoicesJobHandler.Payload(day)`) and drain the worker.
- English: the header switch sets the `NEXT_LOCALE` cookie; the viewer asks for `?lang=en`. Documents read before 1.1 show the German view until revalidated.
- Logo and accent colour: Settings → Rechnungen; only ZUGFeRD files change. Multipart endpoints get a small PNG in the contract-driven harnesses (`MultipartFile.png()` in `ApiTestSupport`).

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

## MARA (job reports add-on)

MARA is built as ERIKA modules plus a Flutter app (ADR 0036, plan: [docs/mara/BUILD_PLAN.md](mara/BUILD_PLAN.md)).

- Back-end modules: `workorders`, `reports`, `media`, `sync`, `reportinvoicing`. In code, MARA's jobs are **work orders**; "job" stays the background job.
- Mobile contract: `api/mobile.yaml` (bearer tokens, `/api/v1/mobile/**`), generated into `de.erika.contract.mobile` by `./gradlew openApiGenerateMobile` (part of every build). `MobileContractIT` fails for any token operation without a fixture in the mobile harness.
- `GET /api/v1/mobile/status` answers without a token: protocol versions, minimum and recommended app version (`ERIKA_MOBILE_MIN_APP_VERSION`, `ERIKA_MOBILE_RECOMMENDED_APP_VERSION`) and the server time.
- Shared rules: test vectors in `mara-rules/` are read by Kotlin (`./gradlew test --tests '*VectorsTest'`) and Dart (`flutter test`). Change the vector first, then both implementations.
- The app: [mobile/README.md](../mobile/README.md). Without a local Flutter, the pinned Docker image gives the same results as CI.
- **Feature switch:** `erika.features.mara` (`ERIKA_FEATURE_MARA`) is off by default and on in the `local` and `test` profiles. When off, the MARA endpoints answer 404 `FEATURE_DISABLED` and the web app hides the "Aufträge" area; `GET /auth/session` reports it as `features.mara`.
- **Office endpoints (Stage 2)** are in `api/openapi.yaml`, tag `workorders`: `/sites`, `/staff-levels`, `/staff`, `/checklist-templates`, `/work-orders`. Customers and people come from `creator.CustomerDirectory` and `identity.WorkspaceContacts.workforce()`.
- **Technicians** (role `TECHNICIAN`, ADR 0039) are invited like other members, but the web sign-in answers 403 `TECHNICIAN_USE_APP`; they never get a web session and aren't offered for workspace switching. Hourly rates and "sees prices" live in `staff_level` / `staff_profile` (owner only).
- Web pages: `/work-orders` (list), `/work-orders/calendar?day=YYYY-MM-DD` (day view by person, drag to reassign or use the "Verschieben zu" menu), `/settings/staff`, `/settings/checklists`.
- **App sign-in (Stage 3, ADR 0037):** `api/mobile.yaml`, tag `mobile-auth`. Bearer tokens only under `/api/v1/mobile/**` (own stateless filter chain, `MobileSecurityConfiguration`). Device tokens and devices are in `mobile_device` / `mobile_token`; tests write them directly (see `MobileContractIT`) or sign in through the API (`support/MobileSupport.kt`). Owners see and revoke phones at `/settings/devices`.
- **Trying the app sign-in locally:** invite a technician in the web app, sign up with the link from Mailpit, then sign in in the app on the emulator: it sets up 2FA (enter the shown key in an authenticator app), shows the recovery codes and asks for a PIN. Revoke the phone at `/settings/devices`; the app deletes its data when it next reaches the server (on return to the foreground or on unlock).
- **Sync (Stage 4, ADR 0038):** `POST /mobile/sync/push` and `GET /mobile/sync/pull` (`sync.SyncEngine`). A syncable record implements `sync.SyncEntity` in its own module, and every change goes through `sync.ChangeFeed.changed`, usually from a listener on the module's audit event. Back-end tests use `support/SyncSupport.kt` (`TestSyncDevice`). `SyncFaultIT` takes `SYNC_FAULT_RUNS` and `SYNC_FAULT_SEED` (the seed of a failed run is in the error message). `SyncNetworkFaultIT` starts a toxiproxy container.
- **If Gradle reports `UP-TO-DATE` or `NO-SOURCE` for tests that should run**, or fails with `Storage corrupted`, the VS Code Java extension is building in `backend/build` at the same time. Run `./gradlew --stop`, delete `backend/build/kotlin`, and add `--no-build-cache` once, or close the Java extension while running `check`.

## VS Code

The Java extension imports the Gradle project and may compile into `backend/build` at the same time as a terminal build, which shows up as errors like "Could not delete …/build/classes" or "Incremental compilation failed". Re-run the build. If it keeps happening, let one tool build at a time.

`backend/gradle.properties` turns off the Kotlin plugin's usage statistics: with a large `~/.gradle/kotlin-profile` directory they stall every build for minutes at the end.

## Contract-first workflow

1. Edit `api/openapi.yaml`; lint with `npx @redocly/cli lint --config api/redocly.yaml api/openapi.yaml`.
2. Back end: implement the regenerated interface (compile errors show what's missing).
3. Front end: `pnpm gen:api` updates the typed client.
