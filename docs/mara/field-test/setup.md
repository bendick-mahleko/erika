# Field-test setup

## Environment: staging

The field test runs on **staging** with the add-on switched on. MARA's switch (`erika.features.mara`) is per installation, not per workspace; turning it on in production would show the add-on to every customer. Per-workspace booking follows in Stage 10.

1. On the staging host, set in the environment file used by `infra/staging/deploy.sh`:
   - `ERIKA_FEATURE_MARA=true`
   - `ERIKA_S3_MEDIA_BUCKET` (photos; already required)
   - `ERIKA_SENTRY_DSN` (API and worker errors to GlitchTip, if not set yet)
2. Deploy, then check `GET https://<staging>/api/v1/auth/session` shows `features.mara: true` when signed in.
3. Create the GlitchTip project **mara-app** (platform Flutter) and keep its DSN for the app build ([distribution.md](distribution.md)).
4. Check the alert `ErikaMaraSyncFailureRate` reaches the ops channel: staging is scraped by the same Prometheus (see [docs/ops/monitoring.md](../../ops/monitoring.md)).

> Staging must hold real personal data during the field test (customers' names and addresses, photos of their homes, working times). Before day 1, confirm staging gets the production protections that matter for that data: backups ([docs/ops/backup.md](../../ops/backup.md)), the same access rules, and deletion of the field-test workspaces afterwards unless a firm moves to production. **Owner decision; to be confirmed before onboarding.**

## The three firms

| Trade | Firm | Contact | Technicians | Phones (model, OS) | Start |
|---|---|---|---|---|---|
| Electrician | | | | | |
| Plumber (Sanitär/Heizung) | | | | | |
| Painter | | | | | |

Selection: 2–8 people, at least one job a day with a customer signature, at least one site without mobile reception (basement, new build), willing to give 15 minutes a week.

## Per firm, before onboarding

- [ ] Data-processing agreement (Auftragsverarbeitungsvertrag) signed, covering the field test on staging ([docs/ops/gdpr.md](../../ops/gdpr.md)).
- [ ] Field-test agreement: free of charge, no availability promise, data deleted 30 days after the end unless they move to production, feedback in return.
- [ ] Beta invitation sent (`infra/production/invite.py` with the staging secret, ADR 0030).
- [ ] Owner signs up, completes the workspace and invoice settings (logo, bank details) and staff levels with hourly rates.
- [ ] Technicians invited as TECHNICIAN; each installs the app from Google Play internal testing or TestFlight ([distribution.md](distribution.md)).
- [ ] Checklist templates for their trade created together.

## Onboarding day (1–2 h on site)

1. Owner: one real work order for today, assigned to a technician.
2. Technician: sign in on the phone (2FA set-up, PIN), sees the work order.
3. Together on the job: times, materials, photos, a signature from the customer (with the customer's consent to the test).
4. Office: the report appears, the PDF is archived; optional: invoice from the report.
5. Show the "Sync" status and the inbox; explain what to do when something looks wrong (call the field-test contact; never uninstall the app with unsent changes).

## During the test

- Twice a week: the field-test report SQL ([docs/ops/mara.md](../../ops/mara.md#sync-runs-failing-erikamarasyncfailurerate)) and GlitchTip project "mara-app"; anything odd goes into [findings.md](findings.md).
- Weekly call per firm (15 min): what got in the way, what took too long. Into [findings.md](findings.md).
- New app builds: through the release workflow; tell the firms when to update.

## After the test

- Final report: success rate per firm and overall, offline test results, findings, decisions.
- Delete the field-test workspaces on staging 30 days after the end (account closure, then the regular deletion), unless a firm moves to production.
