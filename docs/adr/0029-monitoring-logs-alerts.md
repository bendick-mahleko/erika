# 0029. Monitoring, logs, error tracking and the status page

- Status: Accepted
- Date: 2026-11-23

## Context
Software Design §8 names:
- Prometheus and Grafana (JVM, request latency, queue length, failed jobs, disk);
- self-hosted GlitchTip;
- Loki with 14 days of logs and no invoice content;
- alerts by email or push for: site down, 5xx rate > 1 %, intake queue older than 5 minutes, backup failed, audit-chain check failed, certificate due to expire.

NF-06 asks for 99.5 % availability with a status page and uptime monitoring. Software Design §7 says IP addresses are truncated after 7 days.

Until Stage 6 the worker had no metrics endpoint, so "audit chain broken" and "archive integrity failure" could only reach GlitchTip.

## Decision
**A separate small ops server** (ADR 0027) runs:
- Prometheus (45 days), Alertmanager and the Blackbox exporter;
- Grafana, Loki and GlitchTip (with its own PostgreSQL and Valkey);
- Uptime Kuma for the public status page;
- Caddy.

The monitoring therefore keeps working, and the status page stays up, when the app server is down. Grafana and GlitchTip have their own sign-in. Uptime Kuma exposes only the status page; its admin UI is reachable through an SSH tunnel.

**Metrics.**

| Source | What it gives |
|---|---|
| API actuator (management port 8081) | JVM, HTTP and job-queue gauges |
| **Worker `/metrics` (port 8082, new)** | A small JDK HTTP server serving the worker's `PrometheusMeterRegistry`: audit chain, integrity checks, processing failures, JVM |
| Caddy metrics | Requests per status code, for the 5xx rate |
| node_exporter | Hosts, plus backup results through the textfile collector |
| postgres_exporter | Including `pg_stat_archiver`; runs as role `erika_monitor` (pg_monitor only) |
| Blackbox | HTTPS health from outside: availability and certificate expiry; SMTP with STARTTLS on the MX |

**Alerts** (`infra/monitoring/alerts.yml`, 21 rules):
- the six from the design;
- what keeps them trustworthy: application or target down, backup missing or metrics absent, WAL archiving failing, restore drill overdue, disks;
- a `Watchdog` alert that always fires and goes to an external heartbeat URL (dead man's switch), so a dead monitoring stack is noticed.

`page` alerts go by email and, optionally, push (Pushover). `ticket` alerts go by email. Every rule names its runbook. **promtool unit tests** in CI prove that the alerts fire: site down, 5xx rate, backup failed and missing, audit chain broken, check failed, certificate, intake queue, watchdog.

**Logs.**
- Grafana Alloy on the app and database servers ships container logs to Loki over the private network.
- Every line is sent twice: `{ip="full"}` is kept 7 days, and `{ip="masked"}` (IPv4 /24, IPv6 after three groups) 14 days. Loki's per-stream retention does the rest.
- Caddy's access log drops query strings and all headers. The application never logs invoice content (CLAUDE.md), and PostgreSQL logs no statement parameters (ADR 0027).

**Errors.** GlitchTip on the ops server, DSN in `ERIKA_SENTRY_DSN`, `send-default-pii=false`, events kept 30 days. Nothing leaves our servers, so there is no extra sub-processor.

## Consequences
- The ops server is a single point of failure for monitoring. The external heartbeat covers it, and its only data is the heartbeat itself.
- Status page monitors (Uptime Kuma) are configured once in its UI (docs/ops/monitoring.md#status-page), because it has no configuration files.
- Grafana dashboards are provisioned from `infra/monitoring/grafana/dashboards` and can't be edited in the UI. Changes go through Git.
- Pushover (push) and the heartbeat service receive only alert names and host names, no personal data.
