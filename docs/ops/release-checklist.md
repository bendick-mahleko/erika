# Release checklist

For every production release (tag `vX.Y.Z`). Copy it into the release's GitHub issue or PR and tick it off.

## Before tagging
- [ ] CI is green on `main`: back end (incl. tenant isolation, modularity, conformance), front end, e2e, infrastructure checks, dependency scan (no new high findings).
- [ ] OpenAPI version bumped if the contract changed; no breaking change without a version bump.
- [ ] Migrations are expand-only (no column or table dropped or renamed that the previous release still uses). Contract steps come one release later.
- [ ] New auditable changes publish an `AuditableEvent`; new tenant tables have RLS (both enforced by tests).
- [ ] German texts for everything new; English at least as placeholders.
- [ ] No invoice content in new log lines or exception messages.
- [ ] Changelog entry, written for users, in German.
- [ ] Validation packs: any official release in the last 30 days that isn't handled yet? ([validation-pack-update.md](validation-pack-update.md), NF-15)

## Deploy
- [ ] Tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
- [ ] Staging deploy green; smoke test on staging: sign in, upload a sample XRechnung, open the viewer.
- [ ] Approve the `production` job. `deploy.sh` checks WAL archiving first and rolls back on a failed health check.
- [ ] Note the point-in-time restore target printed by `deploy.sh`, in case a migration must be undone.

## After deploying
- [ ] `https://erika-app.de/api/v1/health` shows the new version and status UP.
- [ ] Grafana: 5xx rate and latency normal for 15 minutes. GlitchTip: no new error types.
- [ ] Smoke test in production with the team's test workspace: upload, view, change status, download the original.
- [ ] Beta users informed about visible changes (release notes by email or in-app).

## Monthly (first working day)
- [ ] Restore drill succeeded (`ErikaRestoreDrillOverdue` is quiet; report in `/var/log/erika/`); drill record updated.
- [ ] Availability last month ≥ 99.5 % (Grafana / status page), with incidents noted.
- [ ] Dependency updates (Dependabot) reviewed and merged.
- [ ] Validation pack sources checked for releases.
- [ ] Beta feedback triaged ([beta.md](beta.md#feedback)).
