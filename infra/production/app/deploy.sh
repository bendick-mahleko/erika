#!/usr/bin/env bash
# Deploys image tag $1 on the production app server, checks health, rolls back on failure.
# Called by CI (job deploy-production, after manual approval) over the private network:
#   ssh erika@10.0.1.10 "cd /opt/erika && ./deploy.sh 0.4.0"
#
# Before switching, the database server confirms that WAL archiving works (pgBackRest
# check), so a point-in-time restore to "just before this deployment" is possible.
# Rollback is safe because Flyway migrations are always backward compatible
# (expand, then contract — Software Design §8).
set -euo pipefail

NEW_TAG="${1:?usage: deploy.sh <image-tag>}"
# Called through an SSH forced command (ADR 0027): accept a version and nothing else.
if [[ ! "$NEW_TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Not a release version: $NEW_TAG" >&2
  exit 2
fi
cd "$(dirname "$0")"
STATE_FILE=.deployed-tag
PREVIOUS_TAG="$(cat "$STATE_FILE" 2>/dev/null || true)"

set -a; source .env; set +a

deploy() {
  export ERIKA_IMAGE_TAG="$1"
  docker compose pull api worker web mail
  docker compose up -d --remove-orphans
}

healthy() {
  # 1) API readiness on the loopback management port, 2) public health through Caddy.
  for _ in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8081/actuator/health/readiness >/dev/null 2>&1 &&
       curl -fsS "https://${ERIKA_DOMAIN}/api/v1/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  return 1
}

echo "Deploying $NEW_TAG (previous: ${PREVIOUS_TAG:-none})"
if [[ -n "${ERIKA_DB_SSH:-}" ]]; then
  echo "Checking WAL archiving on the database server"
  ssh -o BatchMode=yes "$ERIKA_DB_SSH" "sudo /opt/erika/backup/pg-backup.sh check"
fi
echo "Point-in-time restore target for this deployment: $(date -u '+%Y-%m-%d %H:%M:%S+00')"
deploy "$NEW_TAG"

if healthy; then
  echo "$NEW_TAG" > "$STATE_FILE"
  docker image prune -f >/dev/null
  echo "Deployed $NEW_TAG"
else
  echo "Health check failed for $NEW_TAG" >&2
  docker compose logs --tail 200 api worker >&2 || true
  if [[ -n "$PREVIOUS_TAG" ]]; then
    echo "Rolling back to $PREVIOUS_TAG" >&2
    deploy "$PREVIOUS_TAG"
    healthy && echo "Rollback to $PREVIOUS_TAG healthy" >&2
  fi
  exit 1
fi
