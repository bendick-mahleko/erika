# 0022. Archive: write-once originals, retention and integrity checks

- Status: Accepted
- Date: 2026-11-22

## Context
AR-01, AR-02, BR-02 and BR-03 require:
- originals stored unchanged and provably so,
- no deletion before the retention date,
- a retention date stored per document.

Spike S1-a (ADR 0013) showed that Hetzner Object Storage supports Object Lock in COMPLIANCE mode when it is enabled at bucket creation.

## Decision
- **Key and identity.** An original is stored under `ws/<workspace>/<sha256>`. `stored_object` has the primary key `(workspace_id, sha256)`, so the same bytes in one workspace are stored once and are never shared across workspaces. The row records the S3 **version ID**. Reads use that version, so a later write to the same key (which only adds a version) can never change what ERIKA serves.
- **Lock.** Each version is written with S3 Object Lock in COMPLIANCE mode until the end of its retention day. The request carries Content-MD5, as S3 requires for lock headers. Storing the same bytes again with a later document date extends the lock and the row's date, never shortens them. `erika.archive.object-lock=NONE` is the documented fallback for a provider without Object Lock. Protection then rests on bucket permissions, and deletion would happen only through the retention job under a separate key.
- **Retention (BR-02).** Retention ends on 31 Dec of the year of issue plus 8 years. For invoices dated before 1 Jan 2025 it is plus 10 years. When the issue date is unknown (plain PDF, unreadable file), the receipt date counts. That last point is still to be confirmed with the tax advisor (build plan §6, gap 7).
- **Database rights.** `erika_app` has no DELETE or TRUNCATE on `stored_object`, and may UPDATE only `legal_hold`, `last_verified_at` and `retention_until`. A trigger allows `retention_until` only to grow. Row-level security as for every tenant table.
- **Integrity.**
  - Every `open` recomputes the SHA-256 and refuses content that doesn't match.
  - A worker job (`archive.verify-integrity`) re-hashes a random 1 % of all originals weekly, and all of them once a year (5 Jan). It uses the `SECURITY DEFINER` function `stored_object_sample` to see across workspaces.
  - A failure is logged at ERROR (sent to GlitchTip), counted in `erika.archive.integrity.failures`, written to the workspace's audit log in its own transaction (so it survives the rollback), and fails the job.
- **Legal hold.** A flag per original, with each change audited. The deletion job skips held originals.
- **Deletion (designed, not built).** No original expires before 31 Dec 2033, so the nightly deletion job is built later. Its design:
  - it selects `retention_until < today AND NOT legal_hold`, per workspace;
  - it runs under a separate S3 key that may delete versions, which the app key can't;
  - it audits every deletion before deleting, then deletes the S3 versions, then the row, which needs a dedicated database role with DELETE;
  - it skips any original whose workspace asked to keep data longer.

## Consequences
- The app's S3 identity needs write, read and list rights plus permission to set and read object retention on the originals bucket, but no delete permission. Locally this is `infra/local/seaweedfs/s3.json`. In production it is the provider's key policy (Stage 7).
- A full yearly check reads every original once. It runs in one job transaction, which is acceptable at current volumes. Batching per workspace is the next step if it gets too long.
- The 1 % weekly sample is random. Over a year it covers about 40 % of originals, and the yearly full check covers the rest.
