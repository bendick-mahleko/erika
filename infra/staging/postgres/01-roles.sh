#!/bin/sh
# Staging database bootstrap: same roles as infra/local/postgres/01-roles.sql,
# with passwords from the environment instead of dev defaults.
set -eu

psql -v ON_ERROR_STOP=1 -U postgres -d erika \
  -v owner_pw="$ERIKA_DB_OWNER_PASSWORD" -v app_pw="$ERIKA_DB_APP_PASSWORD" <<'SQL'
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
SQL
