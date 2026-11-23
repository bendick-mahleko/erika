#!/bin/sh
# Wraps the official entrypoint. With ERIKA_WAL_ARCHIVE=on, every finished WAL segment is
# pushed to the pgBackRest repository (continuous archiving, ADR 0028):
#   - archive_timeout forces a segment switch at least every ERIKA_ARCHIVE_TIMEOUT seconds
#     while there are writes, which bounds the data loss (RPO) to that interval;
#   - archive-async with a local spool queue keeps commits fast while the repository is slow;
#     PGBACKREST_ARCHIVE_PUSH_QUEUE_MAX stops the queue from filling the disk if it is down.
#
# A restored copy (restore drill) must NOT set ERIKA_WAL_ARCHIVE: it would push its own
# timeline into the production repository.
set -eu

if [ "${ERIKA_WAL_ARCHIVE:-off}" = "on" ] && [ "${1:-}" = "postgres" ]; then
  : "${PGBACKREST_STANZA:?PGBACKREST_STANZA must be set when ERIKA_WAL_ARCHIVE=on}"
  set -- "$@" \
    -c wal_level=replica \
    -c archive_mode=on \
    -c "archive_command=pgbackrest --stanza=${PGBACKREST_STANZA} archive-push %p" \
    -c "archive_timeout=${ERIKA_ARCHIVE_TIMEOUT:-300}" \
    -c max_wal_senders=3
fi

exec docker-entrypoint.sh "$@"
