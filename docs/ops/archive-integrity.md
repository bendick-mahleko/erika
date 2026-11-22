# Runbook: archive integrity check failed

Alert sources:
- GlitchTip error "Archive integrity check failed: workspace … object …"
- Prometheus `increase(erika_archive_integrity_failures_total[1d]) > 0`
- a FAILED job of type `archive.verify-integrity`
- a user who sees "ARCHIVE_INTEGRITY_FAILED" when opening a document

Background: [ADR 0022](../adr/0022-archive-write-once-originals.md).

## What it means
An original in object storage no longer matches the SHA-256 recorded when it was stored. The reason is either `object missing` or `content hash mismatch`. Originals are locked in COMPLIANCE mode, so this should be impossible. Treat it as serious.

## Steps
1. **Keep the evidence.** Don't change rows or objects. Note the workspace ID and SHA-256 from the log or the audit entry `archive.integrity.failed`.
2. Check the object and its versions with the admin key: `aws s3api list-object-versions --prefix ws/<workspace>/<sha256>`. Compare the `version_id` in `stored_object` with what exists, and check the object's retention (`get-object-retention`).
3. `object missing`: the version is gone. Check the provider's status and incident reports, and whether the bucket's Object Lock configuration is intact.
4. `content hash mismatch`: download the version, compute `sha256sum`, and compare it with the second-location replica (backups, Stage 7).
5. Restore from the replica if it matches. Record the restore as an incident; the audit log keeps the failure entry.
6. Inform the affected customer: the original is their legal document (GoBD). If the cause could be an attack, follow the security-incident process (GDPR breach assessment within 72 hours).
