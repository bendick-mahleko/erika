# 0019. Audit log as a hash chain per workspace

- Status: Accepted
- Date: 2026-11-22

## Context
NF-10 asks for an audit log of logins, status changes, exports and deletions that is tamper-evident. The design (§7) specifies `hash = SHA-256(prev_hash || entry)`, a daily job that verifies the chain and writes the head hash to an external location, and an alert when the check fails.

## Decision
- **Feeding the log.** Modules publish domain events that implement `platform.audit.AuditableEvent` (workspace, actor, action, details). The `audit` module records every such event synchronously, in the publisher's transaction. A change and its audit entry therefore commit together or not at all.
- **One chain per workspace.** Each entry has `seq` (1, 2, 3, … without gaps), `prev_hash` and `hash`. A transaction-scoped advisory lock per workspace serialises writers.
- **Canonical form.** The hash covers `prev_hash` followed by JSON with sorted keys and no whitespace: action, actor, timestamp (ISO 8601, microseconds), details, seq and workspace. It is written by a fixed JSON mapper that application settings can't change. A unit test pins one hash value. Changing the format would invalidate every stored chain.
- **Append-only.** `UPDATE`, `DELETE` and `TRUNCATE` on `audit_log` are revoked from `erika_app`. Only the schema owner could change rows, and the chain check would detect it.
- **Daily check** (worker job `audit.verify-chains`, 03:30 Europe/Berlin):
  1. It recomputes every hash and checks that `seq` has no gaps.
  2. It compares each chain with the head hashes exported on the previous run. Removing the newest entries leaves a shorter chain that is valid in itself; the exported head reveals it.
  3. If everything is valid, it exports today's heads (`audit-heads/<date>.json`) to the backup bucket.

  On failure it logs at ERROR (sent to GlitchTip), sets the gauge `erika.audit.chain.broken` (alert rule in Prometheus), and the job ends FAILED. The runbook is `docs/ops/audit-chain.md`.
- **What is recorded in Stage 3:**
  - workspace created and updated (with the names of the changed fields)
  - email verified
  - sign-in succeeded (method, new device)
  - sign-in failed (step), recorded in each workspace of the user
  - account locked
  - 2FA set up
  - sign-out

  Later stages add status changes, exports and deletions. Details never contain invoice content, passwords, codes or tokens.

## Consequences
- The external head copy is only as safe as the backup bucket. In production that bucket is at the second location and uses Object Lock (Stage 7). Locally and in tests it is a SeaweedFS bucket.
- A failed sign-in with an unknown email address belongs to no workspace and is not audited; it only counts towards the IP rate limit.
- Retention: audit rows are kept as long as the documents (BR-02). Deleting them needs the same retention job and design as the archive (Stage 4 onwards).
