-- Local/test database bootstrap (runs once as the postgres superuser on first start).
-- DEV-ONLY passwords. Production roles are created by infrastructure code (Stage 7)
-- with generated secrets; the statements are otherwise the same.
--
-- erika_owner : owns the schema, runs Flyway migrations
-- erika_app   : used by the running application; DML only, no BYPASSRLS

CREATE ROLE erika_owner LOGIN PASSWORD 'erika_owner_dev';
CREATE ROLE erika_app   LOGIN PASSWORD 'erika_app_dev'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

REVOKE CONNECT ON DATABASE erika FROM PUBLIC;
GRANT  CONNECT ON DATABASE erika TO erika_owner, erika_app;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA erika AUTHORIZATION erika_owner;
GRANT USAGE ON SCHEMA erika TO erika_app;

ALTER ROLE erika_owner SET search_path = erika;
ALTER ROLE erika_app   SET search_path = erika;
