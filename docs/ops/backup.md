# Runbook: backups

Background: [ADR 0028](../adr/0028-backups-and-restore.md). Restoring: [restore.md](restore.md).

| What | How | When | Kept | Where |
|---|---|---|---|---|
| PostgreSQL WAL | `archive-push` (async) | continuously, at least every 5 min while there are writes | with the full backups | `erika-prod-pgbackrest` (nbg1), encrypted |
| PostgreSQL full backup | `pg-backup.sh full` | nightly at 01:30 | 35 days | same |
| WAL archive check | `pg-backup.sh check` | hourly and before every deployment | – | – |
| Originals and audit heads | `replicate-originals.sh` (rclone copy, never deletes) | nightly at 02:30 | permanent; COMPLIANCE lock 30 days | `erika-prod-*-replica` (nbg1) |
| Restore drill | `restore-drill.sh` into a temporary volume | 1st Sunday of each month, 04:00 | report in `/var/log/erika/` | db server |

Timers on the database server: `systemctl list-timers 'erika-*'`. Logs: `journalctl -u erika-pg-backup-full`. State of the repository:
```sh
cd /opt/erika && docker compose exec -u postgres postgres pgbackrest --stanza=erika info
```

Every job writes its result to `/var/lib/node_exporter/textfile/erika_backup_*.prom`. Grafana shows it on "ERIKA – Überblick → Sicherungen".

## Alerts

### Backup failed (`ErikaBackupFailed`)
The last run of a job failed (`erika_backup_last_status == 0`).
1. `journalctl -u erika-pg-backup-full -n 100` (or `erika-pg-check`, `erika-replicate-originals`).
2. Typical causes:
   - **Object storage unreachable or key invalid**: `pgbackrest info` fails with an S3 error. Check the status of Hetzner Object Storage and the keys in SOPS.
   - **Disk full on the db server**: `df -h /mnt/pgdata`.
   - **Replication: "immutable file modified"**: an original in the primary bucket no longer matches its replica. Originals never change, so treat it as an integrity incident:
     1. Find the key in the log.
     2. Compare its SHA-256 with `stored_object.sha256`.
     3. Follow [archive-integrity.md](archive-integrity.md).

     Don't delete the replica.
3. Run it again by hand: `sudo systemctl start erika-pg-backup-full` (or the affected service). The alert clears with the next successful run.

### Backup missing (`ErikaBackupMissing`, `ErikaBackupMetricsAbsent`)
No successful full backup or replication for 26 hours, or no WAL check for 2 hours, or no results at all.
1. Is the timer active? `systemctl list-timers 'erika-*'`. If it's missing, run `ansible-playbook site.yml --limit db`.
2. Is node_exporter reading the directory? `curl -s 10.0.1.20:9100/metrics | grep erika_backup`.
3. Then proceed as for "backup failed".

### WAL archiving failing (`ErikaWalArchivingFailing`)
PostgreSQL can't push WAL segments; the RPO grows with every minute.
1. `docker compose logs --tail 100 postgres | grep -i archive`
2. `docker compose exec -u postgres postgres pgbackrest --stanza=erika check`
3. The async queue holds up to 8 GiB (`PGBACKREST_ARCHIVE_PUSH_QUEUE_MAX`). Beyond that pgBackRest drops WAL to protect the disk, and point-in-time restore over the gap is no longer possible. Once archiving works again, **take a full backup immediately**: `sudo systemctl start erika-pg-backup-full`.

## Recovery kit and keys
- The pgBackRest cipher pass and the backup project's keys are in SOPS and in the offline recovery kit ([production.md](production.md#secrets)).
- The backup project's keys exist only on the database server. The app server can't read or delete backups.
