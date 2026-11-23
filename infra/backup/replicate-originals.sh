#!/usr/bin/env bash
# Nightly copy of the originals and the audit-chain heads to the second location (NF-05,
# ADR 0028). Runs on the database server, which alone holds the backup project's keys.
#
#   primary:  Hetzner Object Storage fsn1 (the archive, Object Lock COMPLIANCE)
#   replica:  Hetzner Object Storage nbg1, separate project and keys, default retention
#
# `rclone copy` never deletes at the destination, and --immutable fails instead of
# overwriting an object that changed: originals are write-once, so a changed object means
# something is wrong and must be looked at (alert ErikaBackupFailed).
#
# Remotes come from /opt/erika/rclone.env (RCLONE_CONFIG_PRIMARY_* / RCLONE_CONFIG_REPLICA_*).
set -euo pipefail

ENV_FILE="${ERIKA_RCLONE_ENV:-/opt/erika/rclone.env}"
TEXTFILE_DIR="${ERIKA_TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"
IMAGE="${ERIKA_RCLONE_IMAGE:-rclone/rclone:1.71.2}"

# shellcheck source=metrics.sh
source "$(dirname "$0")/metrics.sh"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

copy() {
  local source="$1" target="$2" net=()
  # Local tests only: the Docker network of the S3 container.
  [[ -n "${ERIKA_DOCKER_NETWORK:-}" ]] && net=(--network "$ERIKA_DOCKER_NETWORK")
  docker run --rm "${net[@]}" --env-file "$ENV_FILE" "$IMAGE" copy "primary:$source" "replica:$target" \
    --immutable --checksum --transfers 8 --s3-no-check-bucket --stats 0 --log-level NOTICE
}

start=$(date +%s)
status=0
copy "${ERIKA_S3_BUCKET:?}" "${ERIKA_REPLICA_ORIGINALS_BUCKET:?}" || status=$?
if [[ $status -eq 0 ]]; then
  copy "${ERIKA_BACKUP_BUCKET:?}" "${ERIKA_REPLICA_AUDIT_BUCKET:?}" || status=$?
fi

write_backup_metrics "$TEXTFILE_DIR" originals replica "$status" "$start"
if [[ $status -ne 0 ]]; then
  echo "Replication failed with exit code $status" >&2
  exit "$status"
fi
echo "Replication finished in $(($(date +%s) - start)) s"
