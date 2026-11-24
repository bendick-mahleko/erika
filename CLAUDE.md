# ERIKA – notes for Claude Code

Plan: `docs/BUILD_PLAN.md` (stages, exit criteria, traceability). Decisions: `docs/adr/`. Setup: `docs/dev-setup.md`.

## Commands
- Back end (from `backend/`): `./gradlew check` (format + all tests; needs Docker), `./gradlew spotlessApply`, `./gradlew test --tests '*JobWorkerIT'`.
- Front end (from `frontend/`): `pnpm lint`, `pnpm typecheck`, `pnpm format`, `pnpm build`, `pnpm test:e2e`.
- invoice-core (from `invoice-core/`): `./gradlew check`, `./gradlew publishToMavenLocal`; own rules in `invoice-core/CLAUDE.md`.
- Local stack: `docker compose -f infra/local/compose.yml [--profile app] up -d [--build]`.
- Infrastructure (Stage 7): alert tests `promtool test rules infra/monitoring/tests/alerts.test.yml` (Docker, see dev-setup); local restore drill `infra/backup/drill/run-local-drill.sh`; production runbooks in `docs/ops/`.

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
- Viewer data (view JSON, PDF view copy, attachments) is built by the worker; the API never parses or renders files (ADR 0025). Downloads go through the `download` helper (attachment disposition, nosniff, strict CSP).
- Status changes only via `documents.Documents.changeStatus` (history + audit); the original never changes.
- Sign-up needs accepted terms and, while `erika.signup.mode=INVITE_ONLY`, a valid beta invitation or workspace invitation (ADR 0030, 0032). ADVISOR may write only `POST /feedback`, `POST /documents/{id}/comments` and `POST /exports` (`AdvisorReadOnlyIT` checks every other write operation); don't add others.
- Infrastructure is code (ADR 0027–0029): change `infra/` and run the checks of CI job `infra`; never put secrets in plain text (SOPS: `infra/production/secrets.sops.yaml`). New alerts need a promtool test and a runbook section.
- Legal texts are German content modules in `frontend/src/content/legal/` (not messages); keep `draft: true` until a lawyer has reviewed them.
- Invoice creator (ADR 0031): invoices are issued only by `creator.InvoiceIssuer` in the worker (number, generate, validate, archive in one transaction); issued drafts and `invoice` rows never change; corrections only by credit note/cancellation. Outgoing statuses only via `Documents.changeStatus`.
- POSTs that create or send something take an `Idempotency-Key` (`platform.web.Idempotency`); drafts and master data use ETag/If-Match (`Etags`).
- Every file `invoice-core`'s `InvoiceWriter` produces must pass `GeneratedInvoicesConformanceIT` (KoSIT + veraPDF) for all formats and VAT cases.
- Provider callbacks live under `/webhooks/**` (`api/webhooks.yaml`), authenticated by a shared secret, never a session.
- Errors: RFC 9457 problem details with a stable `code` (`ErikaException`).
- No invoice content in logs or exception messages.
- UI texts in `frontend/messages/{de,en}.json`; German first.
- Membership and closure are checked on every signed-in request (`WorkspaceAccessInterceptor`); a closed workspace is read-only except exports, feedback and `/auth`. Comments are append-only.
- Exports (ADR 0032) are built by the worker; originals only via `Archive.open`, documents via `documents.DocumentExports`; the full export collects `platform.export.AccountDataSection` beans (each module exports its own tables). Downloads are 303 redirects to pre-signed URLs; ZIPs expire after 7 days.
- Billing (ADR 0033): Stripe only through `billing.StripeClient` (pinned API version, Idempotency-Key); webhook events are verified by signature, stored by ID in `stripe_event` and processed by the worker. A READ_ONLY workspace may still read, export and pay (`EntitlementInterceptor`). ERIKA's own invoices only via `creator.SystemInvoices`; never call Stripe or send ERIKA invoices from the API request path except Checkout/portal sessions.
- Security (ADR 0034): forms always `method="post"`; the web app's CSP is set in `frontend/src/proxy.ts` (new browser-facing origins go into `ERIKA_CSP_CONNECT_SRC`); new passwords go through `identity.PasswordPolicy`. CI fails on ZAP rules marked FAIL in `.zap/rules.tsv` and on fixable HIGH/CRITICAL image vulnerabilities (Trivy). Tests that run the worker drain the queue (`while (worker.runOnce() > 0)`).
- Spring Boot 4 / Jackson 3 (`tools.jackson.*`), not Jackson 2.
