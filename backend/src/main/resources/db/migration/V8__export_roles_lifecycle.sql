-- Stage 9: export, roles and account lifecycle (EX-01, EX-02, EX-04, NF-09), ADR 0032.
-- Every table follows the tenancy rules of V2 (checked by TenancyDatabaseIT).

-- ---------------------------------------------------------------------------
-- identity: account closure (EX-04). A closed workspace is read-only; its data stays until
-- the last retention date of its originals (delete_after). Closing can't be undone by the app.
-- (First, because the functions below read closed_at.)
-- ---------------------------------------------------------------------------
ALTER TABLE workspace ADD COLUMN closed_at timestamptz;
ALTER TABLE workspace ADD COLUMN closed_by uuid;
ALTER TABLE workspace ADD COLUMN delete_after date;

-- ---------------------------------------------------------------------------
-- identity: invitations to a workspace (EX-02). Only the SHA-256 of the emailed token is
-- stored. Owners are not invited directly: an invited member can be promoted later.
-- ---------------------------------------------------------------------------
CREATE TABLE invitation (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    email        text        NOT NULL,
    role         text        NOT NULL CHECK (role IN ('MEMBER', 'ADVISOR')),
    token_hash   bytea       NOT NULL UNIQUE,
    invited_by   uuid        NOT NULL REFERENCES app_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    accepted_at  timestamptz,
    accepted_by  uuid        REFERENCES app_user (id),
    revoked_at   timestamptz
);
CREATE INDEX invitation_workspace_idx ON invitation (workspace_id, created_at DESC);
-- At most one open invitation per address and workspace (a new one revokes the old one).
CREATE UNIQUE INDEX invitation_open_uq ON invitation (workspace_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

ALTER TABLE invitation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invitation
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON invitation FROM erika_app;

-- The invitee has no session in the inviting workspace yet: the link's token is the only
-- key. SECURITY DEFINER runs as the table owner; it returns one invitation by token hash.
CREATE FUNCTION invitation_by_token(p_hash bytea)
    RETURNS TABLE (id uuid, workspace_id uuid, workspace_name text, workspace_closed boolean, email text, role text,
                   expires_at timestamptz, accepted_at timestamptz, revoked_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT i.id, i.workspace_id, w.name, w.closed_at IS NOT NULL, i.email, i.role, i.expires_at, i.accepted_at, i.revoked_at
          FROM invitation i JOIN workspace w ON w.id = i.workspace_id
         WHERE i.token_hash = p_hash
    $$;

-- The workspaces a user belongs to, with names and roles: for the session (workspace
-- switcher) without widening the request's RLS scope to the user's other workspaces.
CREATE FUNCTION workspaces_of_user(p_user uuid)
    RETURNS TABLE (id uuid, name text, role text, closed boolean)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT w.id, w.name, m.role, w.closed_at IS NOT NULL
          FROM membership m JOIN workspace w ON w.id = m.workspace_id
         WHERE m.user_id = p_user
         ORDER BY m.created_at, m.id
    $$;

-- ---------------------------------------------------------------------------
-- identity: account closure (EX-04), continued.
-- ---------------------------------------------------------------------------

-- Once closed, the workspace row can't change any more (not even to reopen it).
CREATE FUNCTION workspace_closure_final() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'workspace % is closed and read-only', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER workspace_closure_final
    BEFORE UPDATE ON workspace
    FOR EACH ROW EXECUTE FUNCTION workspace_closure_final();

-- Mail to a closed workspace's intake address is refused like mail to an unknown address.
CREATE OR REPLACE FUNCTION intake_workspace_for_token(p_token text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT a.workspace_id FROM intake_address a JOIN workspace w ON w.id = a.workspace_id
         WHERE a.token = lower(p_token) AND w.closed_at IS NULL
    $$;

-- ---------------------------------------------------------------------------
-- documents: comments by the team and the tax advisor (EX-02). Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE document_comment (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL,
    document_id   uuid        NOT NULL,
    author_id     uuid        NOT NULL,
    body          text        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    created_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);
CREATE INDEX document_comment_document_idx ON document_comment (workspace_id, document_id, created_at);

ALTER TABLE document_comment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_comment
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON document_comment FROM erika_app;

-- ---------------------------------------------------------------------------
-- export: DATEV settings and export jobs (EX-01, EX-04)
-- ---------------------------------------------------------------------------
CREATE TABLE datev_settings (
    workspace_id             uuid        PRIMARY KEY REFERENCES workspace (id),
    consultant_number        integer     CHECK (consultant_number BETWEEN 1001 AND 9999999),  -- Beraternummer
    client_number            integer     CHECK (client_number BETWEEN 1 AND 99999),           -- Mandantennummer
    chart                    text        NOT NULL DEFAULT 'SKR03' CHECK (chart IN ('SKR03', 'SKR04')),
    fiscal_year_start_month  integer     NOT NULL DEFAULT 1 CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    account_length           integer     NOT NULL DEFAULT 4 CHECK (account_length BETWEEN 4 AND 8),
    -- NULL: the chart's default account (DatevAccounts)
    expense_account          text        CHECK (expense_account ~ '^[0-9]{4,9}$'),
    revenue_account          text        CHECK (revenue_account ~ '^[0-9]{4,9}$'),
    creditor_account         text        CHECK (creditor_account ~ '^[0-9]{4,9}$'),
    debtor_account           text        CHECK (debtor_account ~ '^[0-9]{4,9}$'),
    version                  integer     NOT NULL DEFAULT 0,
    updated_at               timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE datev_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON datev_settings
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON datev_settings FROM erika_app;

-- One export: a period (ZIP with originals, index and DATEV CSV) or the whole account.
-- The ZIP is a temporary object in the exports bucket, deleted at expires_at.
CREATE TABLE data_export (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    kind          text        NOT NULL CHECK (kind IN ('PERIOD', 'FULL')),
    period_from   date,
    period_to     date,
    scope         text        NOT NULL DEFAULT 'ALL' CHECK (scope IN ('IN', 'OUT', 'ALL')),
    state         text        NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'READY', 'FAILED', 'EXPIRED')),
    requested_by  uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    expires_at    timestamptz,
    storage_key   text,
    file_name     text,
    size_bytes    bigint,
    sha256        text        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    document_count integer,
    error         text,
    CHECK (kind = 'FULL' OR (period_from IS NOT NULL AND period_to IS NOT NULL AND period_from <= period_to))
);
CREATE INDEX data_export_workspace_idx ON data_export (workspace_id, created_at DESC);

ALTER TABLE data_export ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON data_export
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON data_export FROM erika_app;

-- The daily clean-up runs without a workspace: which exports have expired (IDs only).
CREATE FUNCTION expired_exports()
    RETURNS TABLE (workspace_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT e.workspace_id, e.id FROM data_export e WHERE e.state = 'READY' AND e.expires_at < now() ORDER BY e.expires_at $$;

REVOKE ALL ON FUNCTION invitation_by_token(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invitation_by_token(bytea) TO erika_app;
REVOKE ALL ON FUNCTION workspaces_of_user(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION workspaces_of_user(uuid) TO erika_app;
REVOKE ALL ON FUNCTION expired_exports() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION expired_exports() TO erika_app;
