# ERIKA – Guide for operators and support

This guide is for the people who **run** ERIKA in production and **answer users**. It gives the overview and the rules, then points to the runbooks in [docs/ops/](../ops/), which hold the exact commands.

## 1. What runs where

| Server | Location | Runs |
|---|---|---|
| **app** | Hetzner nbg1 | Caddy (TLS), web app, API, worker, intake mail server (Postfix), ClamAV |
| **db** | Hetzner fsn1, no public IP | PostgreSQL 16 with pgBackRest, encrypted volume |
| **ops** | Hetzner fsn1 | Prometheus, Alertmanager, Grafana, Loki, GlitchTip, Uptime Kuma (status page), CI runner |

Object storage holds the originals (write-once), uploads, derived files and exports in one project; backups and replicas in a second project at another location. Everything is code: Terraform and Ansible in `infra/`; secrets encrypted with SOPS.

Background: [production.md](../ops/production.md), ADRs [0027](../adr/0027-production-environment.md)–[0029](../adr/0029-monitoring-logs-alerts.md).

### The two back-end roles
- **API:** answers the browser. It never parses invoice files.
- **Worker:** does the background jobs: virus scan, reading, validating, archiving, sending email, issuing invoices, exports, Stripe events, and the daily due-date reminders and recurring invoices.

If the worker stops, users see uploads stuck "in progress", no emails, and invoices stuck in "Wird ausgestellt …". The job queue alerts fire.

## 2. Daily and weekly routine

| When | What | Runbook |
|---|---|---|
| daily | glance at Grafana "ERIKA – Überblick" and GlitchTip; check the status page is green | [monitoring.md](../ops/monitoring.md) |
| daily (automatic) | backups (continuous WAL archiving, nightly full backup at 01:30), originals replicated to the second location at 02:30 | [backup.md](../ops/backup.md) |
| nightly / weekly (automatic) | audit chain check (nightly 03:30), archive integrity check (Sundays 04:00, plus a yearly full pass); alerts on failure | [archive-integrity.md](../ops/archive-integrity.md), [audit-chain.md](../ops/audit-chain.md) |
| monthly | automatic restore drill (1st Sunday, 04:00); read and record the report | [restore.md](../ops/restore.md) |
| per release | release checklist, deploy, smoke check, rollback if needed | [release-checklist.md](../ops/release-checklist.md), [production.md](../ops/production.md#routine-work) |
| per official rule release | new validation pack within 30 days | [validation-pack-update.md](../ops/validation-pack-update.md) |

## 3. When an alert fires

Alerts with severity `page` go by email and push; `ticket` by email only. Every alert has a section in [monitoring.md](../ops/monitoring.md) that says how to check and what to do. The usual first steps:

1. Is it real? Check from outside and on the status page.
2. Did a deployment just happen? Roll back to the previous image tag. Migrations only ever add, so the old version runs against the new schema.
3. Look at GlitchTip (exceptions) and Grafana logs.
4. If users are affected for more than 5 minutes, post an incident on the status page.
5. Silence known issues only with an end time and a comment.

Targets: data loss at most about 5 minutes (design target 15 minutes), restore within 4 hours ([ADR 0028](../adr/0028-backups-and-restore.md)).

## 4. Support: answering users

### Ground rules
- **Never ask for passwords or 2FA codes.** ERIKA staff never need them.
- **Customer data is the customer's.** Don't look into a workspace's documents unless the owner asked you to, in writing, for a specific problem. Use the support access procedure in [beta.md](../ops/beta.md#support-access) and note what you looked at.
- **Only answer to the account's email address.** Never send data to another address.
- **Originals can't be deleted or changed, not even by us.** That is a legal requirement (GoBD), not a bug.

### Common questions

| User says | What to check / answer |
|---|---|
| "No confirmation email" | Spam folder; correct address; mail provider logs. The link is valid for 48 hours; they can sign up again after it expires. |
| "I'm locked out" | 15 minutes after 5 failed attempts. Lost phone: sign in with a recovery code. No recovery codes left: check their identity from the account address, then escalate to the operator (there is no self-service reset of the second factor, and no written procedure yet). |
| "Upload stays in progress" | Worker and ClamAV health, job queue in Grafana ([intake.md](../ops/intake.md)) |
| "My supplier's invoice is red" | Explain that the invoice breaks the official rules. The viewer explains each rule and offers "Korrektur anfordern". If they think the rule result is wrong, collect the file (with consent) and check it against the KoSIT validator. |
| "Emails to my intake address don't arrive" | Allow-list on? Look in *Einstellungen → E-Mail-Eingang → Quarantäne*. Otherwise: intake logs ([intake.md](../ops/intake.md)), size over 25 MB, a virus finding. |
| "Please delete my data" | GDPR procedure: [gdpr.md](../ops/gdpr.md). Invoices are kept until their retention ends; account data is deleted. |
| "My workspace is read-only" | Trial ended or payment failed: *Einstellungen → Abrechnung* ([billing.md](../ops/billing.md)). Reading and export always work. |
| "I closed my account by mistake" | It can't be undone in the app. Escalate to the operator. |

### Beta specifics
Inviting users, the welcome text, feedback triage and the beta gate: [beta.md](../ops/beta.md), [beta-triage.md](../ops/beta-triage.md). Feedback from the in-app button arrives in the beta mailbox.

## 5. Launch and billing

- Go-live checklist and launch-day steps: [go-live.md](../ops/go-live.md)
- Stripe setup, trial ends, failed payments, ERIKA's own invoices: [billing.md](../ops/billing.md)

## 6. Access and secrets

- SSH only with personal keys. Admin passwords and keys live in `infra/production/secrets.sops.yaml` (age-encrypted).
- The offline recovery kit (printed age key and backup cipher pass) is described in [production.md](../ops/production.md#secrets). Keep it current when administrators change.
- Never put secrets in tickets, chat or the repository in plain text.
