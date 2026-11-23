#!/usr/bin/env bash
# Local restore drill, end to end (docs/ops/restore.md):
#   1. copy the local development database into the drill database (WAL archiving on)
#   2. full backup with pg-backup.sh (as the nightly timer does)
#   3. a change AFTER the backup, archived as WAL only
#   4. restore-drill.sh: newest state → the change must be there (backup + WAL replay)
#   5. restore-drill.sh --target-time before the change → the change must be missing (PITR)
#
#   infra/backup/drill/run-local-drill.sh            # needs the local stack's postgres running
#   ERIKA_DRILL_SOURCE=<container> ...              # another source database container
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: don't rewrite /paths in docker arguments

cd "$(dirname "$0")"
SOURCE="${ERIKA_DRILL_SOURCE:-erika-postgres-1}"
WORK="${ERIKA_DRILL_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
# A native path for docker on Windows (pwd -W), the normal one elsewhere.
ERIKA_COMPOSE_DIR="$(pwd -W 2>/dev/null || pwd)"
export ERIKA_COMPOSE_DIR
export ERIKA_TEXTFILE_DIR="$WORK/textfile"
export ERIKA_DRILL_REPORT_DIR="$WORK/reports"
export ERIKA_POSTGRES_IMAGE=erika-postgres:local
DB=erika-drill-postgres-1
NETWORK=erika-drill_default
psql_db() { docker exec "$DB" psql -U postgres -d erika -At -v ON_ERROR_STOP=1 -c "$1"; }

echo "== Drill workspace: $WORK"
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
docker compose up -d --build --wait postgres

echo "== 1. Copy $SOURCE into the drill database"
docker exec "$SOURCE" pg_dump -U postgres -d erika -Fc >"$WORK/source.dump"
docker exec -i "$DB" pg_restore -U postgres -d erika --exit-on-error <"$WORK/source.dump"
: >"$WORK/expect.txt"
for table in workspace app_user document stored_object parsed_invoice validation_result audit_log; do
  n=$(docker exec "$SOURCE" psql -U postgres -d erika -Atc "SELECT count(*) FROM erika.$table")
  echo "$table $n" | tee -a "$WORK/expect.txt"
done

echo "== 2. Full backup"
../pg-backup.sh full
grep -v '^#' "$ERIKA_TEXTFILE_DIR"/erika_backup_postgres_full.prom

echo "== 3. A change after the backup (only in the WAL archive)"
sleep 2
BEFORE_CHANGE=$(psql_db "SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS.USOF')")
sleep 2
psql_db "CREATE TABLE erika.drill_marker (at timestamptz NOT NULL DEFAULT now()); INSERT INTO erika.drill_marker DEFAULT VALUES;" >/dev/null
echo "drill_marker 1" >>"$WORK/expect.txt"
../pg-backup.sh check
grep -v '^#' "$ERIKA_TEXTFILE_DIR"/erika_backup_postgres_archive_check.prom

echo "== 4. Restore the newest state"
../restore-drill.sh --env-file pgbackrest.env --network "$NETWORK" --expect "$WORK/expect.txt"

echo "== 5. Point-in-time restore to $BEFORE_CHANGE (before the change)"
ERIKA_DRILL_ID=erika-restore-drill-pitr ../restore-drill.sh --env-file pgbackrest.env --network "$NETWORK" \
  --target-time "$BEFORE_CHANGE" --keep
marker=$(docker exec erika-restore-drill-pitr psql -U postgres -d erika -Atc "SELECT to_regclass('erika.drill_marker') IS NULL")
docker rm -f erika-restore-drill-pitr >/dev/null
docker volume rm erika-restore-drill-pitr >/dev/null
if [[ "$marker" != "t" ]]; then
  echo "FAILED: the point-in-time restore contains the later change" >&2
  exit 1
fi
echo "ok    point-in-time restore stops before the later change"

echo "== Reports"
cat "$ERIKA_DRILL_REPORT_DIR"/*.txt
grep -v '^#' "$ERIKA_TEXTFILE_DIR"/erika_restore_drill.prom
docker compose down -v >/dev/null
echo "== Local restore drill passed"
