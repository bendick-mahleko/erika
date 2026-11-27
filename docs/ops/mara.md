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

## Sync runs failing (ErikaMaraSyncFailureRate)

**What happened:** over the last day, more than 0.5 % of the MARA app's sync runs failed (runs
that stopped for lack of a connection don't count). The numbers come from the phones' content-free
counters (`PUT /mobile/devices/me/telemetry`, table `mobile_telemetry_day`), so they arrive with
a delay of up to an hour. The field-test target is ≥ 99.5 % success (MARA build plan, Stage 9).

**Usual causes:**
- Server errors on `/api/v1/mobile/sync/**` (5xx in the API log, `erika-api` dashboard).
- A token problem: many `401` answers after a release (refresh, device revocation, ADR 0037).
- One phone stuck in a loop (a mutation the server can't process): the counters per device show it.

**What to do:**
1. Find the affected firms and phones, as the schema owner (row-level security doesn't apply):
   ```sql
   SELECT workspace_id, device_id,
          sum((counters->>'syncDone')::int)   AS done,
          sum((counters->>'syncFailed')::int) AS failed
     FROM mobile_telemetry_day
    WHERE day >= current_date - 1
    GROUP BY 1, 2
    ORDER BY failed DESC
    LIMIT 20;
   ```
2. Check the API log around those devices' requests (`device=<id>`; no content is logged) and
   GlitchTip (project "mara-app") for crashes or errors from the app.
3. If one phone fails alone: ask the technician to open the app online and look at the inbox;
   if that doesn't help, revoke and sign the phone in again only after its outbox is empty
   (the outbox is lost with the device's data).
4. If many phones fail after an app release: raise the minimum app version (`ERIKA_MOBILE_MIN_APP_VERSION`)
   once a fixed version is out, or roll the server back.

**Field-test report:** the success rate over the whole field test, per firm:
```sql
SELECT workspace_id,
       sum((counters->>'syncDone')::int)   AS done,
       sum((counters->>'syncFailed')::int) AS failed,
       round(100.0 * sum((counters->>'syncDone')::int)
             / nullif(sum((counters->>'syncDone')::int) + sum((counters->>'syncFailed')::int), 0), 2) AS success_percent,
       sum((counters->>'pushConflict')::int) AS conflicts
  FROM mobile_telemetry_day
 WHERE day BETWEEN :start AND :end
 GROUP BY workspace_id;
```

