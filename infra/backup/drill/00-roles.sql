-- Drill database: only the roles. Schema, extension, tables and grants come from the
-- dump of the source database (pg_restore), as in a real migration of a full database.
CREATE ROLE erika_owner LOGIN PASSWORD 'erika_owner_dev';
CREATE ROLE erika_app LOGIN PASSWORD 'erika_app_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
