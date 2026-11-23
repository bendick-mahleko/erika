# PostgreSQL 16 with pgBackRest (ADR 0028). One image for the restore drill, staging and
# production, so the drill tests what production runs.
#
# pgBackRest is configured entirely through PGBACKREST_* environment variables (see
# infra/production/db/compose.yml); WAL archiving is switched on with ERIKA_WAL_ARCHIVE=on.
FROM postgres:16.15-bookworm

# The Debian-based image already has the PGDG apt repository, which ships pgBackRest.
RUN apt-get update \
 && apt-get install -y --no-install-recommends pgbackrest ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && install -d -o postgres -g postgres -m 0750 /var/lib/pgbackrest /var/spool/pgbackrest /var/log/pgbackrest /tmp/pgbackrest \
 && rm -f /etc/pgbackrest.conf /etc/pgbackrest/pgbackrest.conf

COPY infra/postgres/erika-entrypoint.sh /usr/local/bin/erika-entrypoint.sh
RUN chmod 0755 /usr/local/bin/erika-entrypoint.sh

ENTRYPOINT ["erika-entrypoint.sh"]
CMD ["postgres"]
