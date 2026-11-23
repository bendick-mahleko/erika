# 0028. Backups, replication and restore drills

- Status: Accepted
- Date: 2026-11-23

## Context
NF-05 asks for daily encrypted backups kept 35 days, a second copy at another location, and a monthly restore test, with RPO 24 h and RTO 8 h. The design is stricter: RPO 15 minutes and RTO 4 hours, using pgBackRest with continuous WAL archiving, rclone for the originals, and "a backup location in another data centre, e.g. Storage Box". Build plan gap 6 says to test against the design targets and report against both.

## Decision
**Where.**
- The second location is **Hetzner Object Storage in nbg1**, in a **separate Hetzner project** whose S3 keys exist only on the database server. It isn't a Storage Box, because:
  - pgBackRest speaks S3 natively (a Storage Box would need its SFTP repository or a mount);
  - S3 offers Object Lock for the replicas;
  - everything stays in Germany.
- The primary archive is in fsn1 (ADR 0013).

**PostgreSQL** (image `infra/docker/postgres.Dockerfile` = postgres:16 + pgBackRest from PGDG, configured only through `PGBACKREST_*` variables):
- continuous WAL archiving (`archive-push`, async, spool queue limited to 8 GiB) with `archive_timeout=300`, so at most 5 minutes of writes exist only on the server;
- a full backup every night at 01:30, kept 35 days with its WAL;
- AES-256 client-side encryption (`repo1-cipher-type`): the provider never sees plain data;
- an hourly `pgbackrest check`, which forces a WAL switch and waits until it is in the repository. It runs before every production deployment too.

**Originals and audit heads.** `rclone copy --immutable --checksum` runs every night from fsn1 to replica buckets in nbg1:
- it never deletes, and it fails if an original changed, because originals are write-once;
- the replica buckets have Object Lock with 30 days of default COMPLIANCE retention, so stolen backup keys can't delete the copies either.

**Results as metrics.** Each run writes `erika_backup_last_{run,success}_timestamp_seconds`, `erika_backup_last_status` and `erika_backup_duration_seconds` with `kind` and `type` labels, through node_exporter's textfile collector. The alerts are "backup failed", "backup missing" (26 h; 2 h for the WAL check), "WAL archiving failing" (`pg_stat_archiver`) and "restore drill overdue" (35 days).

**Restore drill.** `infra/backup/restore-drill.sh`:
- restores into a **new, empty volume** with `--archive-mode=off` (the copy must not archive into the production repository);
- replays all archived WAL, or up to `--target-time`, and waits until PostgreSQL has promoted;
- checks the result: no failed migration; audit chains without gaps and correctly linked; every document's original recorded; optional minimum row counts;
- times every step.

It runs every month on the database server, on a temporary volume, so production data never leaves production. The first drill runs on staging (build plan Stage 7). `infra/backup/drill/run-local-drill.sh` runs the same scripts locally and in CI (`.github/workflows/postgres-image.yml`):

1. full backup;
2. a change after the backup;
3. restore of the newest state, in which the change must be present;
4. point-in-time restore to before the change, in which it must be missing.

## Consequences
- RPO is at most about 5 minutes while the repository is reachable, within the design's 15 minutes and NF-05's 24 hours. RTO is dominated by download speed: the restore copies the full backup plus WAL. The local drill takes 31 s for 34 MB. Production must be timed at its real size (docs/ops/restore.md), and the target is 4 hours.
- **Losing the pgBackRest cipher pass makes every backup unreadable.** It is in SOPS and in the offline recovery kit.
- A restore can only bring originals back from the replica, not undo Object Lock. A lost original is restored by copying it back from the replica bucket. Its SHA-256 in the database proves it is the right file.
- The database image is pinned separately from app releases (`ERIKA_POSTGRES_IMAGE_TAG`): a new tag restarts the database and is a deliberate step.
- Staging switches from `postgres:16-alpine` to the Debian-based image. Text sort order differs between musl and glibc, so the existing staging database needs a `REINDEX` or a fresh volume.
