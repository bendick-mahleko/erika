-- Platform baseline. Runs as erika_owner (schema owner); the app connects as erika_app.
-- Roles and the schema itself are created outside Flyway (infra/local/postgres, IaC in production).

-- Every table erika_owner creates in this schema is usable (DML only) by the app role.
-- Tables that must be append-only (audit_log, stored_object) revoke UPDATE/DELETE explicitly.
ALTER DEFAULT PRIVILEGES IN SCHEMA erika GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO erika_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA erika GRANT USAGE, SELECT ON SEQUENCES TO erika_app;

-- Background job queue (ADR 0010). System table: not tenant-scoped by RLS, because the
-- worker serves all workspaces. A job that acts for a workspace carries workspace_id, and
-- the worker sets app.workspace_id for the transaction in which the handler runs.
CREATE TABLE job (
    id            uuid        PRIMARY KEY,
    type          text        NOT NULL,
    payload       jsonb       NOT NULL,
    workspace_id  uuid,
    state         text        NOT NULL DEFAULT 'QUEUED'
                              CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts      integer     NOT NULL DEFAULT 0,
    max_attempts  integer     NOT NULL DEFAULT 5 CHECK (max_attempts >= 1),
    run_at        timestamptz NOT NULL DEFAULT now(),
    locked_by     text,
    locked_until  timestamptz,
    last_error    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz
);

-- Due-job lookup for the worker; partial indexes stay small as finished jobs pile up.
CREATE INDEX job_queued_due_idx  ON job (run_at)       WHERE state = 'QUEUED';
CREATE INDEX job_running_idx     ON job (locked_until) WHERE state = 'RUNNING';
CREATE INDEX job_workspace_idx   ON job (workspace_id) WHERE workspace_id IS NOT NULL;

-- Liveness of background components (read by GET /api/v1/health).
CREATE TABLE system_heartbeat (
    component  text        PRIMARY KEY,
    instance   text        NOT NULL,
    last_seen  timestamptz NOT NULL
);
