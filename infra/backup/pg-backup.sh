#!/usr/bin/env bash
# pgBackRest backup or check on the database server (ADR 0028), run by systemd timers:
#   erika-pg-backup-full.timer   nightly   pg-backup.sh full
#   erika-pg-check.timer         hourly    pg-backup.sh check   (WAL archiving works end to end)
#
# Writes the result for node_exporter's textfile collector, which the alerts
# ErikaBackupFailed / ErikaBackupMissing / ErikaWalArchivingFailed read:
#   erika_backup_last_run_timestamp_seconds{kind,type}
#   erika_backup_last_success_timestamp_seconds{kind,type}
#   erika_backup_last_status{kind,type}            1 = ok, 0 = failed
#   erika_backup_duration_seconds{kind,type}
set -euo pipefail

TYPE="${1:?usage: pg-backup.sh full|diff|incr|check}"
COMPOSE_DIR="${ERIKA_COMPOSE_DIR:-/opt/erika}"
TEXTFILE_DIR="${ERIKA_TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"
STANZA="${PGBACKREST_STANZA:-erika}"

# shellcheck source=metrics.sh
source "$(dirname "$0")/metrics.sh"

pgbackrest() {
  docker compose --project-directory "$COMPOSE_DIR" exec -T -u postgres postgres pgbackrest --stanza="$STANZA" "$@"
}

start=$(date +%s)
status=0
case "$TYPE" in
  full | diff | incr)
    # stanza-create is idempotent; it only creates the repository on the first run.
    pgbackrest stanza-create >/dev/null && pgbackrest --type="$TYPE" backup || status=$?
    kind=postgres
    ;;
  check)
    # Forces a WAL switch and waits until the segment is in the repository.
    pgbackrest check || status=$?
    kind=postgres-archive
    ;;
  *)
    echo "unknown type: $TYPE" >&2
    exit 2
    ;;
esac

write_backup_metrics "$TEXTFILE_DIR" "$kind" "$TYPE" "$status" "$start"

if [[ $status -ne 0 ]]; then
  echo "pgBackRest $TYPE failed with exit code $status" >&2
  exit "$status"
fi
echo "pgBackRest $TYPE finished in $(($(date +%s) - start)) s"
