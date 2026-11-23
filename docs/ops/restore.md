# Runbook: restore and restore drills

Background: [ADR 0028](../adr/0028-backups-and-restore.md). Targets:
- data loss (RPO) at most 15 minutes (design) or 24 hours (NF-05);
- restore (RTO) within 4 hours (design) or 8 hours (NF-05).

## Restore drill (monthly)

`infra/backup/restore-drill.sh` restores the newest backup plus all archived WAL into a new, empty Docker volume and starts PostgreSQL on it. **The running database is never touched.** It then checks:
- the Flyway history has no failed migration;
- the audit chains have no gaps, and every entry links to the previous hash;
- every document has its original recorded;
- optionally, minimum row counts (`--expect`).

It times every step.

- **Production:** the timer `erika-restore-drill.timer` runs it on the database server on the first Sunday of each month. The report goes to `/var/log/erika/erika-restore-drill-*.txt`, and the metric `erika_restore_drill_last_success_timestamp_seconds` feeds the alert `ErikaRestoreDrillOverdue`. Run it by hand with `sudo systemctl start erika-restore-drill`. It needs free disk space of about the database size under `/var/lib/docker`.
- **Staging:**
  ```sh
  cd /opt/erika && sudo ERIKA_POSTGRES_IMAGE=ghcr.io/<repo>/postgres:<tag> backup/restore-drill.sh --env-file pgbackrest.env
  ```
- **Locally and in CI:** `infra/backup/drill/run-local-drill.sh`:
  1. copies the local development database into a drill database with WAL archiving to a TLS S3;
  2. takes a full backup;
  3. makes a change after the backup;
  4. restores the newest state (the change must be there);
  5. restores to a point in time before the change (it must be missing).

After every manual drill, add a line to the [drill record](#drill-record).

## Restore after a disaster

Decide first: **whole database** (server lost, data corrupted) or **point in time** (a faulty change, e.g. a bad migration or an operator error)? Keep notes with times for the incident record.

1. **Stop writes.** On the app server: `cd /opt/erika && docker compose stop api worker mail web`. Caddy then answers 502. Announce the incident on the status page.
2. **Database server.** If it's lost, run `terraform apply` (it creates a new server and volume) and then `ansible-playbook site.yml --limit db`. Ansible starts an *empty* database. Stop it again:
   ```sh
   cd /opt/erika && docker compose stop postgres
   ```
3. **Restore into the data directory** (empty it first, or use `--delta` on the existing one):
   ```sh
   sudo mv /mnt/pgdata/data /mnt/pgdata/data.broken-$(date +%F)   # keep the evidence if there's space
   sudo install -d -o 999 -g 999 -m 0700 /mnt/pgdata/data
   docker compose run --rm --no-deps -u postgres --entrypoint pgbackrest postgres \
     --stanza=erika restore                                     # newest state
   #  … restore --type=time --target='2027-01-10 14:00:00+01' --target-action=promote   # point in time
   docker compose up -d postgres
   docker compose logs -f postgres     # "database system is ready to accept connections" after replay
   ```
   WAL archiving stays on: after promotion PostgreSQL archives into a new timeline, and pgBackRest handles that.
4. **Check.** Run the drill checks against the restored database. The SQL is in `restore-drill.sh`, section "Checks". Also run the audit chain check (`audit.verify-chains` runs every night; a lost tail since the last exported head shows as `exported head no longer matches`; see [audit-chain.md](audit-chain.md)).
5. **Take a full backup right away:** `sudo systemctl start erika-pg-backup-full`.
6. **Start the app again:** `docker compose up -d` on the app server. Check `https://erika-app.de/api/v1/health`.
7. **Originals.** The database refers to originals by SHA-256; nothing needs restoring there unless the primary bucket was lost. In that case copy from the replica:
   ```sh
   rclone copy replica:erika-prod-originals-replica primary:<new originals bucket> --immutable
   ```
   The new bucket must be created with Object Lock (ADR 0013). The worker's integrity check confirms each file by its hash.
8. **Afterwards.**
   - Write the incident record: what was lost (the time between the last archived WAL and the incident), the duration, the cause.
   - Data loss affecting customers: GDPR breach assessment within 72 hours and notice to customers (processor duty, data-processing agreement § 8).

## Drill record

| Date | Where | Data | Files restored | WAL replay and promotion | Checks | Total | Result |
|---|---|---|---|---|---|---|---|
| 2026-11-23 | local (run-local-drill.sh, pgBackRest 2.60.1, TLS S3) | dev database: 101 workspaces, 180 documents, 1,175 audit entries; 34 MB | 12 s | 8 s | 11 s | **31 s** | OK; the change after the backup was present (WAL replay) |
| 2026-11-23 | local, point in time before the change | same | 7 s | 9 s | 6 s | **22 s** | OK; the change was absent as intended |
| _open_ | **staging** (first drill, build plan Stage 7) | | | | | | |
| _open_ | production, first monthly drill after go-live | | | | | | |

**Estimate for production.** The restore time is dominated by downloading the full backup. pgBackRest restores in parallel (`process-max`); with zstd compression and a few hundred Mbit/s from Hetzner Object Storage, 10 GB of database takes well under 30 minutes. WAL replay for one day of activity takes minutes. The 4-hour target therefore holds with a wide margin at beta scale. The staging drill must confirm this with real network transfer; record the measured throughput here.
