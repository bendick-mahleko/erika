#!/usr/bin/env bash
# Restore drill (NF-05, ADR 0028; runbook docs/ops/restore.md).
#
# Restores the newest pgBackRest backup plus all archived WAL (or up to --target-time)
# into a NEW, empty Docker volume, starts PostgreSQL on it, waits until recovery has
# finished and checks the result. The running database is never touched.
#
#   restore-drill.sh --env-file /opt/erika/pgbackrest.env [--target-time '2027-01-10 14:00:00+01']
#                    [--network NAME] [--expect FILE] [--keep]
#
#   --env-file   PGBACKREST_* settings of the repository (same as the database container)
#   --network    Docker network for the drill containers (local drill: the S3 network)
#   --expect     file with lines "<table> <min rows>": each table needs at least that many rows
#   --keep       leave the restored container running for inspection
#
# Writes a report to $ERIKA_DRILL_REPORT_DIR and, for node_exporter, the metrics
# erika_restore_drill_last_success_timestamp_seconds and erika_restore_drill_duration_seconds.
set -euo pipefail

ENV_FILE=""
TARGET_TIME=""
NETWORK=""
EXPECT=""
KEEP=0
IMAGE="${ERIKA_POSTGRES_IMAGE:-erika-postgres:local}"
STANZA="${PGBACKREST_STANZA:-erika}"
REPORT_DIR="${ERIKA_DRILL_REPORT_DIR:-/var/log/erika}"
TEXTFILE_DIR="${ERIKA_TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --target-time) TARGET_TIME="$2"; shift 2 ;;
    --network) NETWORK="$2"; shift 2 ;;
    --expect) EXPECT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$ENV_FILE" ]] || { echo "--env-file is required" >&2; exit 2; }

ID="${ERIKA_DRILL_ID:-erika-restore-drill-$(date +%Y%m%d-%H%M%S)}"
VOLUME="$ID"
REPORT="$REPORT_DIR/$ID.txt"
mkdir -p "$REPORT_DIR"
NET_ARGS=()
[[ -n "$NETWORK" ]] && NET_ARGS=(--network "$NETWORK")

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$REPORT"; }
psql_drill() { docker exec "$ID" psql -U postgres -d erika -At -v ON_ERROR_STOP=1 -c "$1"; }

cleanup() {
  if [[ $KEEP -eq 0 ]]; then
    docker rm -f "$ID" >/dev/null 2>&1 || true
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  else
    log "Kept container $ID (volume $VOLUME). Remove with: docker rm -f $ID && docker volume rm $VOLUME"
  fi
}
trap cleanup EXIT

t0=$(date +%s)
log "Restore drill $ID"
log "Image $IMAGE, stanza $STANZA, target ${TARGET_TIME:-newest (end of archived WAL)}"

# 1. Restore files from the repository into the empty volume.
RESTORE_ARGS=(--stanza="$STANZA" --archive-mode=off --log-level-console=info)
if [[ -n "$TARGET_TIME" ]]; then
  RESTORE_ARGS+=(--type=time "--target=$TARGET_TIME" --target-action=promote)
fi
docker volume create "$VOLUME" >/dev/null
docker run --rm "${NET_ARGS[@]}" --env-file "$ENV_FILE" -v "$VOLUME:/var/lib/postgresql/data" \
  --entrypoint sh "$IMAGE" -c '
    set -e
    chown postgres:postgres /var/lib/postgresql/data && chmod 0700 /var/lib/postgresql/data
    exec gosu postgres pgbackrest restore "$@"' sh "${RESTORE_ARGS[@]}" 2>&1 | grep -E "INFO: (restore|repo1)|ERROR|WARN" | tee -a "$REPORT"
t1=$(date +%s)
log "Files restored in $((t1 - t0)) s"

# 2. Start PostgreSQL on the copy. No ERIKA_WAL_ARCHIVE: the copy must not archive into
#    the production repository. It replays WAL through archive-get, then promotes.
docker run -d --name "$ID" "${NET_ARGS[@]}" --env-file "$ENV_FILE" -e POSTGRES_PASSWORD=unused \
  -v "$VOLUME:/var/lib/postgresql/data" "$IMAGE" >/dev/null
for _ in $(seq 1 720); do
  if docker exec "$ID" pg_isready -U postgres -q 2>/dev/null &&
     [[ "$(docker exec "$ID" psql -U postgres -Atc 'SELECT pg_is_in_recovery()' 2>/dev/null)" == "f" ]]; then
    break
  fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$ID")" != "true" ]]; then
    docker logs --tail 50 "$ID" 2>&1 | tee -a "$REPORT"
    log "FAILED: PostgreSQL stopped during recovery"
    exit 1
  fi
  sleep 5
done
[[ "$(psql_drill 'SELECT pg_is_in_recovery()')" == "f" ]] || { log "FAILED: recovery did not finish within an hour"; exit 1; }
t2=$(date +%s)
log "Recovery finished (WAL replayed, promoted) in $((t2 - t1)) s"

# 3. Checks.
failures=0
check() { # check <description> <sql returning 0 for ok>
  local result
  result=$(psql_drill "$2")
  if [[ "$result" == "0" ]]; then log "ok    $1"; else log "FAIL  $1 ($result)"; failures=$((failures + 1)); fi
}

newest=$(psql_drill "SELECT coalesce(to_char(max(at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), 'none') FROM erika.audit_log")
log "Newest audit entry in the copy: $newest UTC"
for table in workspace app_user document stored_object parsed_invoice validation_result audit_log; do
  log "rows  $table: $(psql_drill "SELECT count(*) FROM erika.$table")"
done
check "Flyway history has no failed migration" "SELECT count(*) FROM erika.flyway_schema_history WHERE NOT success"
check "audit chain: sequence numbers without gaps per workspace" \
  "SELECT count(*) FROM (SELECT workspace_id FROM erika.audit_log GROUP BY workspace_id HAVING max(seq) <> count(*)) gaps"
check "audit chain: every entry links to the previous hash" \
  "SELECT count(*) FROM erika.audit_log a JOIN erika.audit_log p ON p.workspace_id = a.workspace_id AND p.seq = a.seq - 1 WHERE a.prev_hash <> p.hash"
check "every document has its original recorded" \
  "SELECT count(*) FROM erika.document d LEFT JOIN erika.stored_object s ON s.workspace_id = d.workspace_id AND s.sha256 = d.sha256 WHERE s.sha256 IS NULL"
if [[ -n "$EXPECT" ]]; then
  while read -r table min; do
    [[ -z "$table" || "$table" == \#* ]] && continue
    check "$table has at least $min rows" "SELECT CASE WHEN count(*) >= $min THEN 0 ELSE count(*) END FROM erika.$table"
  done <"$EXPECT"
fi

t3=$(date +%s)
log "Checks done in $((t3 - t2)) s"
log "TOTAL restore time (files + recovery + checks): $((t3 - t0)) s — target 4 h (design), 8 h (NF-05)"

if [[ $failures -gt 0 ]]; then
  log "RESULT: FAILED ($failures check(s))"
  exit 1
fi
log "RESULT: OK"
mkdir -p "$TEXTFILE_DIR"
{
  echo "# HELP erika_restore_drill_last_success_timestamp_seconds Last successful restore drill."
  echo "# TYPE erika_restore_drill_last_success_timestamp_seconds gauge"
  echo "erika_restore_drill_last_success_timestamp_seconds $t3"
  echo "# HELP erika_restore_drill_duration_seconds Duration of the last successful restore drill."
  echo "# TYPE erika_restore_drill_duration_seconds gauge"
  echo "erika_restore_drill_duration_seconds $((t3 - t0))"
} >"$TEXTFILE_DIR/erika_restore_drill.prom.tmp" && mv "$TEXTFILE_DIR/erika_restore_drill.prom.tmp" "$TEXTFILE_DIR/erika_restore_drill.prom"
