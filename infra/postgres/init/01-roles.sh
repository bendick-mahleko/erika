#!/bin/sh
# Database bootstrap for staging and production (runs once, on an empty data directory):
# same roles as infra/local/postgres/01-roles.sql, with passwords from the environment.
#   erika_owner    owns the schema; Flyway migrations run as this role
#   erika_app      the application: no DDL, no BYPASSRLS (ADR 0011)
#   erika_monitor  postgres_exporter: pg_monitor only, no access to tenant data (ADR 0029)
set -eu

psql -v ON_ERROR_STOP=1 -U postgres -d erika \
  -v owner_pw="$ERIKA_DB_OWNER_PASSWORD" -v app_pw="$ERIKA_DB_APP_PASSWORD" \
  -v monitor_pw="${ERIKA_DB_MONITOR_PASSWORD:-}" <<'SQL'
CREATE ROLE erika_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE erika_app   LOGIN PASSWORD :'app_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

REVOKE CONNECT ON DATABASE erika FROM PUBLIC;
GRANT  CONNECT ON DATABASE erika TO erika_owner, erika_app;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA erika AUTHORIZATION erika_owner;
GRANT USAGE ON SCHEMA erika TO erika_app;

ALTER ROLE erika_owner SET search_path = erika;
ALTER ROLE erika_app   SET search_path = erika;
-- Trigram search on supplier names (AR-04). Extensions need a superuser; the migration
-- (V5) only checks that it exists.
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA erika;

-- Metrics only (pg_stat_*, pg_stat_archiver); no USAGE on schema erika, so no table access.
SELECT CASE WHEN :'monitor_pw' <> '' THEN 1 END AS create_monitor \gset
\if :{?create_monitor}
CREATE ROLE erika_monitor LOGIN PASSWORD :'monitor_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE IN ROLE pg_monitor;
GRANT CONNECT ON DATABASE erika TO erika_monitor;
\endif
SQL
