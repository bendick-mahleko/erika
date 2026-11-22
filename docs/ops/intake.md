# Runbook: intake (upload and email)

Background: [ADR 0023](../adr/0023-intake-pipeline-and-documents.md) (pipeline), [ADR 0024](../adr/0024-email-intake.md) (email).

Flow: upload or email → (email: raw message archived) → `intake.process-upload` / `intake.process-mail` (virus scan, unzip, archive, document) → `documents.parse-and-validate` → inbox.

## Alerts

Rules are in `infra/monitoring/alerts.yml`.

### Intake queue older than 5 minutes (`ErikaIntakeQueueStuck`)
Jobs of type `intake.*` or `documents.parse-and-validate` have been due for more than 5 minutes. IN-01 promises the inbox within 2 minutes.
1. Is the worker running? Check `GET /api/v1/health` (component `worker`) and `docker compose logs worker`.
2. Is ClamAV up? Check `docker compose ps clamav`. While ClamAV is down, intake jobs fail and retry, because the scan fails closed. ClamAV needs about 1.2 GB of memory and a minute to load signatures after a restart.
3. Look for jobs failing over and over:
   ```sql
   SELECT type, attempts, last_error FROM job WHERE state = 'QUEUED' AND attempts > 0 ORDER BY run_at LIMIT 20;
   ```
4. Is the worker overloaded (many large ZIPs or PDFs)? Start a second worker instance; jobs are claimed with `SKIP LOCKED`.

### Failed jobs (`ErikaJobsFailed`)
```sql
SELECT id, type, workspace_id, last_error, finished_at FROM job WHERE state = 'FAILED' ORDER BY finished_at DESC LIMIT 20;
```
- `documents.parse-and-validate`: after 5 attempts the document is marked `processing = FAILED` and shows "Prüfung fehlgeschlagen". Find the cause in the logs (job ID in the MDC). Once fixed, requeue:
  ```sql
  UPDATE job SET state = 'QUEUED', attempts = 0, run_at = now() WHERE id = '<id>';
  ```
  Also reset the document: `UPDATE document SET processing = 'PENDING'`. Do this as `erika_owner`, with the workspace set.
- `intake.process-*`: the upload or email stays RECEIVED. Requeue the job as above; the job is idempotent.

### Virus found
These are not alerts, but they are audited (`intake.virus.detected`) and logged at WARN with the signature name. Infected files are never archived. If they cluster around one sender, consider blocking that sender at the mail server.

## Mail server

- Logs: `docker compose logs mail`. Postfix logs to stdout.
- Policy rejections (`Recipient address unknown`) are normal: spam to random addresses.
- `4.3.0 ERIKA intake not reachable`: the API is down or `ERIKA_INTAKE_MAIL_SECRET` differs between the API and the mail container. Postfix keeps the mail and retries for 5 days.
- Queue: `docker compose exec mail postqueue -p`. Flush with `postqueue -f`.

## DNS and deliverability (production, before beta)

1. Spike S5-a: confirm port 25 is open on the Hetzner server, and set reverse DNS for the server's IP to `mx.erika-app.de`.
2. DNS records:
   ```
   mx.erika-app.de.        A     <server IPv4>
   in.erika-app.de.        MX 10 mx.erika-app.de.
   in.erika-app.de.        TXT   "v=spf1 -all"
   _dmarc.in.erika-app.de. TXT   "v=DMARC1; p=reject"
   ```
3. Mount a TLS certificate for `mx.erika-app.de` into the mail container (`TLS_CERT`, `TLS_KEY`).
4. Deliverability test: from a Gmail, an Outlook.com and a GMX account, send an email with the sample XRechnung (`frontend/e2e/fixtures/xrechnung-beispiel.xml`) to a test workspace's address. Each must appear in the inbox within 2 minutes. Record the results (date, provider, time to inbox) in the release notes.
5. Check that `swaks --to nobody@in.erika-app.de --server mx.erika-app.de` is rejected at RCPT with 5.1.1.
