# MARA operations

Runbook sections for the MARA add-on (job reports from the technician app).

## Signature refused (ErikaMaraSignatureHashMismatch)

**What happened:** a phone sent a customer's signature for a report, together with the SHA-256 of
the report as the phone showed it (canonical form, `mara-rules/vectors/report/canonical.json`).
The server rebuilt the report from its own data, got a different hash and refused the signature
(`HASH_MISMATCH`, ADR 0038/0040). The report is not signed on the server; the technician sees the
refusal in the app's inbox.

**Usual causes:**
- Something in the report changed on the server between the technician's last sync and the
  signature: another technician's entry, a renamed customer, a renamed person or checklist item.
- An app version whose canonical form differs from the server's (a bug: both must pass the shared
  vectors).

**What to do:**
1. Find the report: the API log has `Signature for report <id> refused` (no content is logged).
2. Ask the firm what changed (the work order page shows the report; the audit log shows who
   changed what and when, by record ID).
3. If the content is right on the server, the technician syncs and asks the customer to sign
   again, or sends the report unsigned with a reason ("nicht unterschrieben", M-BR-03).
4. If several firms are affected at once after an app release, compare the app's and the server's
   canonical form against the vectors and stop the rollout (minimum app version, ADR 0038).
