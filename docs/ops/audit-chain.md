# Runbook: audit chain check failed

**Alert sources:**
- GlitchTip error "Audit chain broken: workspace … at seq …"
- Prometheus `erika_audit_chain_broken > 0`
- a FAILED job of type `audit.verify-chains`

Background: [ADR 0019](../adr/0019-audit-hash-chain.md).

Suggested Prometheus rule (Stage 7 monitoring):

```yaml
- alert: AuditChainBroken
  expr: erika_audit_chain_broken > 0
  labels: { severity: critical }
  annotations:
    summary: "Audit log hash chain broken in {{ $value }} workspace(s)"
```

## What it means
The audit log of one or more workspaces no longer matches its hashes. Possible causes:
1. Someone changed or deleted rows with database owner rights (manual SQL, a faulty migration).
2. A database restore went back to before the last exported head (the newest entries are "missing").
3. A software bug in how entries are written, e.g. a change to the canonical form.

## Steps
1. **Don't repair anything yet.** Keep the evidence: take a database snapshot and copy the latest `audit-heads/*.json` from the backup bucket.
2. Find the problems in the worker log. Each one names the workspace ID, the sequence number and a reason:
   - `content hash mismatch`
   - `previous hash mismatch`
   - `sequence gap`
   - `exported head no longer matches`
3. Check recent deployments, migrations and restores (cause 2 or 3), and database access logs for owner logins (cause 1).
4. If a restore caused it: write down which entries were lost, between the exported head and the restored state. Document the incident.
5. If tampering is likely, treat it as a security incident: GDPR breach assessment within 72 hours, and inform the affected customers (processor duty).
6. Fixing a broken chain is never done by rewriting old rows. If a new starting point is needed, write it down in the incident record. That needs a tool, which doesn't exist yet: add one with a review if it ever becomes necessary.

The job does not export new heads while a chain is broken, so the last good export stays as the reference.
