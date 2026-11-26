# Runbook: monitoring and alerts

Background: [ADR 0029](../adr/0029-monitoring-logs-alerts.md). Rules: `infra/monitoring/alerts.yml`. Rule tests: `infra/monitoring/tests/alerts.test.yml`.

| Tool | Where | Sign-in |
|---|---|---|
| Grafana (metrics, logs) | https://grafana.erika-app.de, dashboard "ERIKA – Überblick" | admin, password in SOPS |
| GlitchTip (errors) | https://errors.erika-app.de | personal accounts |
| Status page (NF-06) | https://status.erika-app.de | public |
| Uptime Kuma admin | `ssh -L 3001:127.0.0.1:3001 erika@<ops>`, then http://localhost:3001 | admin account |
| Prometheus / Alertmanager | `ssh -L 9090:… / 9093:…` via `docker compose exec`, or Grafana Explore | – |

Severity `page` goes by email and push; `ticket` by email. Silence a known issue in Alertmanager (Grafana → Alerting → Silences, or `amtool`), always with an end time and a comment.

## Alerts

### ErikaSiteDown
`GET https://erika-app.de/api/v1/health`, probed from the ops server, fails for 2 minutes (no answer, 503, or a TLS error).
1. Is it really down? Check from your own machine and on status.erika-app.de.
2. App server: `ssh erika@<app>`, then `cd /opt/erika && docker compose ps`. Restart containers that have stopped: `docker compose up -d`.
3. A 503 from health means the database or object storage is unreachable. The `components` in the JSON say which. For the database, see `ErikaApplicationDown` and the database server.
4. Can't reach the server at all? Check the Hetzner Cloud console (server status, rescue console) and Hetzner's status page.
5. Post an incident on the status page if it lasts longer than 5 minutes.

### ErikaHighErrorRate
More than 1 % of requests answer 5xx for 5 minutes.
1. GlitchTip shows the exceptions. Grafana → "Fehler in den Logs" shows the log lines.
2. Right after a deployment? Roll back: `./deploy.sh <previous version>` ([production.md](production.md#routine-work)).
3. Database overloaded? `docker compose exec postgres psql -U postgres -c "SELECT state, count(*) FROM pg_stat_activity GROUP BY 1"`.

### ErikaCertificateExpiresSoon
A probed certificate expires in less than 14 days. Caddy renews at 30 days, so renewal keeps failing.
1. `docker compose logs caddy | grep -i -E "acme|certificate"` on the server concerned.
2. Common causes:
   - port 80 is blocked (Hetzner firewall);
   - DNS doesn't point to the server;
   - a CAA record forbids Let's Encrypt;
   - Let's Encrypt rate limits.
3. Mail certificate (`mx.`): Postfix reads it from Caddy's store. After a renewal, new SMTP sessions use it; if in doubt, `docker compose restart mail`.

### ErikaMailServerDown
`mx.erika-app.de:25` doesn't answer, or doesn't offer STARTTLS with a valid certificate. Suppliers' servers retry for days, but invoices arrive late (IN-01).
1. `docker compose ps mail clamav` and `docker compose logs --tail 100 mail`.
2. No STARTTLS: the certificate path in `compose.yml` doesn't exist yet. Check that Caddy has the `mx.` certificate, then `docker compose restart mail`.
3. Port 25 blocked: check the Hetzner firewall rule and Hetzner's outbound/inbound port 25 policy for the project (spike S5-a, [intake.md](intake.md)).

### ErikaApplicationDown / ErikaTargetDown
Prometheus can't scrape a target. API or worker → page; exporters → ticket.
1. Is the container running? `docker compose ps`.
2. Private network: from the ops server, `curl -s 10.0.1.10:8081/actuator/health`.
3. The worker serves `/metrics` on 8082 only if `ERIKA_WORKER_METRICS_PORT` isn't `-1`.

### ErikaIntakeQueueStuck, ErikaJobsFailed, ErikaDocumentProcessingFailed
See [intake.md](intake.md).

### ErikaArchiveIntegrityFailure
See [archive-integrity.md](archive-integrity.md).

### ErikaMaraSignatureHashMismatch
See [mara.md](mara.md#signature-refused-erikamarasignaturehashmismatch).

### ErikaAuditChainBroken, ErikaAuditChainCheckFailed
See [audit-chain.md](audit-chain.md).

### ErikaBackupFailed, ErikaBackupMissing, ErikaBackupMetricsAbsent, ErikaWalArchivingFailing
See [backup.md](backup.md).

### ErikaRestoreDrillOverdue
No successful restore drill in 35 days. Run one ([restore.md](restore.md)) and look at why the monthly timer didn't succeed: `journalctl -u erika-restore-drill`.

### ErikaHostDown
node_exporter on a server can't be reached for 3 minutes, which usually means the server is down. Check the Hetzner console. For the database server, the app answers 503 as well.

### ErikaDiskFilling (ticket) / ErikaDiskAlmostFull (page)
Less than 15 % / 5 % free space.
1. `df -h` and `sudo du -xh --max-depth=2 / | sort -h | tail`.
2. Docker: `docker system df`, then `docker image prune -a` for old images.
3. Database volume: WAL spool growing (archiving fails? see backup.md) or data growth. Grow the Hetzner volume, then `sudo cryptsetup resize pgdata && sudo resize2fs /dev/mapper/pgdata`.

### Watchdog (dead man's switch)
Always firing on purpose. Alertmanager sends it every minute to the external heartbeat URL. If the heartbeat service reports it missing, Prometheus or Alertmanager (or the ops server) is down, and **no other alert would reach you**. Check the ops server first.

## Testing the alerts

**Automated, in CI** (`promtool test rules`): site down, 5xx rate, backup failed and missing, audit chain broken, check failed, certificate, intake queue and watchdog fire with the expected labels and texts.

**End to end, after setup and once a quarter.** Note the time; each step should produce an email (and push for `page`) within about 5 minutes. Undo each step afterwards.

| Alert | How to trigger it safely | Undo |
|---|---|---|
| ErikaSiteDown | On the app server: `docker compose stop caddy` (customers see an error, so pick a quiet time and announce maintenance) | `docker compose start caddy` |
| ErikaBackupFailed | On the db server: `echo 'erika_backup_last_status{kind="postgres",type="full"} 0' \| sudo tee /var/lib/node_exporter/textfile/zz_alert_test.prom` | `sudo rm /var/lib/node_exporter/textfile/zz_alert_test.prom` |
| ErikaAuditChainBroken | Never break real audit data. Send the alert straight to Alertmanager on the ops server, which tests routing and delivery: `docker compose exec alertmanager amtool alert add ErikaAuditChainBroken severity=page --annotation=summary="TEST – bitte ignorieren" --alertmanager.url=http://localhost:9093`. The rule is covered by the promtool test, the gauge by `AuditChainIT`. | resolves after 5 minutes |
| Watchdog | `docker compose stop alertmanager` on the ops server, and wait for the heartbeat service to report it | `docker compose start alertmanager` |

Write down the result (date, alert, minutes until notification) in the beta gate checklist ([beta.md](beta.md#beta-gate)).

## Status page

Uptime Kuma has no configuration files; set it up once in its admin UI:
1. Monitors:
   - "ERIKA (Web)": HTTP(s), `https://erika-app.de/api/v1/health`, every 60 s;
   - "E-Mail-Empfang": TCP port, `mx.erika-app.de:25`, every 120 s.
2. Status page, slug `erika`, title "ERIKA – Systemstatus", both monitors, German description. The page is public at https://status.erika-app.de.
3. Maintenance windows and incidents are posted here. They are also the basis for the monthly availability report (NF-06: 99.5 %). Grafana shows the same figure from the Blackbox probe ("Verfügbarkeit 30 Tage").

The front end links the status page in the footer when `ERIKA_STATUS_URL` is set in the web container.
