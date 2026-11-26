# Architecture Decision Records

One file per decision, numbered, never deleted. If a decision changes, write a new ADR that supersedes the old one and update the old one's status. Format: [template](0000-template.md).

ADRs 0001–0008 come from *ERIKA – Software Design v1.1*; later ADRs were made during implementation.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith with Spring Modulith | Accepted |
| [0002](0002-kotlin-jvm-backend.md) | Kotlin/JVM back end | Accepted |
| [0003](0003-shared-schema-rls.md) | Shared schema + row-level security | Accepted |
| [0004](0004-job-queue-in-postgres.md) | Job queue in PostgreSQL | Accepted (refined by 0010) |
| [0005](0005-neutral-invoice-model.md) | Neutral `ParsedInvoice` model as JSONB | Accepted |
| [0006](0006-self-hosted-inbound-mail.md) | Self-hosted inbound Postfix | Accepted |
| [0007](0007-validation-packs-as-data.md) | Validation packs as versioned data | Accepted |
| [0008](0008-server-side-sessions.md) | Server-side sessions | Accepted |
| [0009](0009-spring-boot-4.md) | Spring Boot 4 instead of 3 | Accepted |
| [0010](0010-own-job-table.md) | Own job table instead of JobRunr | Accepted |
| [0011](0011-database-roles-and-schema.md) | Database roles and dedicated schema | Accepted |
| [0012](0012-local-object-storage.md) | SeaweedFS instead of MinIO for local S3 | Accepted |
| [0013](0013-object-lock-provider.md) | Spike S1-a: Object Lock at Hetzner | Accepted |
| [0014](0014-contract-first-tooling.md) | Contract-first tooling | Accepted |
| [0015](0015-invoice-core-integration.md) | How ERIKA uses invoice-core | Accepted (amended by 0016, 0020) |
| [0016](0016-invoice-core-in-this-repository.md) | invoice-core lives in this repository | Accepted (amended by 0020) |
| [0017](0017-sign-in-and-second-factor.md) | Sign-in, second factor and sessions | Accepted |
| [0018](0018-tenant-scope-per-request.md) | Tenant scope per request and the isolation checks | Accepted |
| [0019](0019-audit-hash-chain.md) | Audit log as a hash chain per workspace | Accepted |
| [0020](0020-invoice-core-from-source.md) | The back end builds invoice-core from source | Accepted |
| [0021](0021-validation-packs-and-engine.md) | Validation packs and the validation engine | Accepted |
| [0022](0022-archive-write-once-originals.md) | Archive: write-once originals, retention, integrity | Accepted |
| [0023](0023-intake-pipeline-and-documents.md) | Intake pipeline, uploads and documents | Accepted |
| [0024](0024-email-intake.md) | Email intake: Postfix, policy lookup and pipe to the API | Accepted (production DNS pending S5-a) |
| [0025](0025-viewer-and-derived-objects.md) | Viewer, PDF view copy and derived objects | Accepted |
| [0026](0026-document-status-and-search.md) | Document status, history and search | Accepted |
| [0027](0027-production-environment.md) | Production environment as code (Terraform, Ansible, three servers) | Accepted |
| [0028](0028-backups-and-restore.md) | Backups, replication and restore drills | Accepted |
| [0029](0029-monitoring-logs-alerts.md) | Monitoring, logs, error tracking and the status page | Accepted |
| [0030](0030-closed-beta.md) | Closed beta: invitations, terms, feedback, legal pages | Accepted |
| [0031](0031-invoice-creator.md) | Invoice creator: drafts, issuing in the worker, numbering, sending | Accepted |
| [0032](0032-export-roles-account-lifecycle.md) | Export, roles and account lifecycle | Accepted (DATEV import pending S9-a) |
| [0033](0033-billing-with-stripe.md) | Billing with Stripe: trial, subscription, entitlements, ERIKA's own e-invoices | Accepted (pricing, test-clock run pending) |
| [0034](0034-hardening-and-go-live.md) | Hardening and go-live: password reset, CSP, scans in CI, load test, launch | Accepted |
| [0035](0035-release-1-1.md) | Release 1.1: allow-list and quarantine, due reminders, GiroCode, logo, recurring invoices, English | Accepted |
| [0036](0036-mara-as-erika-modules.md) | MARA (job reports for trades) as modules of ERIKA | Accepted (spikes S1-a, S1-c open) |
| [0037](0037-mobile-sign-in-devices-tokens.md) | Mobile sign-in: devices, bearer tokens, 2FA at registration | Accepted (MARA Stage 3) |
| [0038](0038-mara-sync-protocol.md) | MARA sync protocol: outbox, change feed, field versions | Accepted (MARA Stage 4) |
| [0039](0039-technician-role.md) | The TECHNICIAN role | Accepted |
| [0040](0040-mara-storage-and-retention.md) | MARA storage: device database, photos, signed reports and retention | Proposed (device part MARA Stage 1, photos Stage 6) |
