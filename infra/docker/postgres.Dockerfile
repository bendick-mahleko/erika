# PostgreSQL 16 with pgBackRest (ADR 0028). One image for the restore drill, staging and
# production, so the drill tests what production runs.
#
# pgBackRest is configured entirely through PGBACKREST_* environment variables (see
# infra/production/db/compose.yml); WAL archiving is switched on with ERIKA_WAL_ARCHIVE=on.
FROM postgres:16.15-bookworm

# The Debian-based image already has the PGDG apt repository, which ships pgBackRest.
RUN apt-get update \
 && apt-get install -y --no-install-recommends pgbackrest ca-certificates tini \
 && rm -rf /var/lib/apt/lists/* \
 && install -d -o postgres -g postgres -m 0750 /var/lib/pgbackrest /var/spool/pgbackrest /var/log/pgbackrest /tmp/pgbackrest \
 && rm -f /etc/pgbackrest.conf /etc/pgbackrest/pgbackrest.conf

COPY infra/postgres/erika-entrypoint.sh /usr/local/bin/erika-entrypoint.sh
# The official entrypoint drops root with gosu, a Go binary built with an outdated standard
# library (Trivy: 1 critical, 25 high). util-linux's setpriv does the same job (ADR 0034).
COPY infra/postgres/gosu-setpriv.sh /usr/local/bin/gosu
RUN chmod 0755 /usr/local/bin/erika-entrypoint.sh /usr/local/bin/gosu

# tini is PID 1 and reaps orphans. pgBackRest's asynchronous archive-push leaves a background
# process behind; with PostgreSQL as PID 1 that orphan became a child of the postmaster, and its
# non-zero exit (e.g. repository unreachable) made PostgreSQL restart all sessions (ADR 0034).
# The image's STOPSIGNAL (SIGINT, fast shutdown) is forwarded by tini.
ENTRYPOINT ["/usr/bin/tini", "--", "erika-entrypoint.sh"]
CMD ["postgres"]
