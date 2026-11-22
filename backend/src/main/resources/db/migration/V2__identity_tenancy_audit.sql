-- Stage 3: identity, tenancy and audit (EX-02 core, NF-02, NF-03, NF-10).
--
-- Tenancy (Software Design §7): every tenant table has workspace_id NOT NULL, an index
-- starting with workspace_id, row-level security enabled and a policy using
-- erika.current_workspace_id(). TenantTableLintIT enforces this for every future
-- migration. Tables that are deliberately not tenant-scoped are listed in that test
-- with the reason.

-- The workspace of the current transaction, set by the app with
-- set_config('app.workspace_id', <id>, true) (= SET LOCAL). NULL when not set, so every
-- policy matches no rows. NULLIF: after a SET LOCAL ends, the setting reads as ''.
CREATE FUNCTION current_workspace_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.workspace_id', true), '')::uuid $$;

-- The signed-in (or signing-in) user. Only used to let a user see their own memberships
-- before a workspace is chosen (login).
CREATE FUNCTION current_app_user_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

-- ---------------------------------------------------------------------------
-- Users: global, not tenant-scoped. A user can belong to several workspaces
-- (e.g. a tax advisor); the role lives in membership.
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id                  uuid        PRIMARY KEY,
    email               text        NOT NULL,
    display_name        text        NOT NULL,
    password_hash       text        NOT NULL,           -- Argon2id (NF-02)
    locale              text        NOT NULL DEFAULT 'de' CHECK (locale IN ('de', 'en')),
    email_verified_at   timestamptz,
    totp_secret         text,                           -- AES-GCM encrypted, see TotpSecretCipher
    totp_enabled_at     timestamptz,
    totp_last_step      bigint,                         -- last accepted TOTP time step (replay protection)
    failed_logins       integer     NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_email_uq ON app_user (lower(email));

-- One-time tokens sent by email (verification). Only the SHA-256 of the token is stored.
CREATE TABLE user_token (
    token_hash  bytea       PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    purpose     text        NOT NULL CHECK (purpose IN ('VERIFY_EMAIL')),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);
CREATE INDEX user_token_user_idx ON user_token (user_id);

-- 2FA recovery codes, single use. Only the SHA-256 is stored: the codes are random
-- (about 60 bits), so a slow password hash adds nothing.
CREATE TABLE recovery_code (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    code_hash   bytea       NOT NULL,
    used_at     timestamptz
);
CREATE INDEX recovery_code_user_idx ON recovery_code (user_id);

-- Devices a user has signed in from (for the new-device notification). Only a hash of
-- the random device cookie is stored.
CREATE TABLE known_device (
    user_id      uuid        NOT NULL REFERENCES app_user (id),
    device_hash  bytea       NOT NULL,
    first_seen   timestamptz NOT NULL DEFAULT now(),
    last_seen    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_hash)
);

-- ---------------------------------------------------------------------------
-- Workspace: the tenant itself. RLS on id instead of workspace_id; a user may also
-- see the workspaces they are a member of (login, workspace picker).
-- ---------------------------------------------------------------------------
CREATE TABLE workspace (
    id            uuid        PRIMARY KEY,
    name          text        NOT NULL,
    street        text,
    postal_code   text,
    city          text,
    country_code  text        NOT NULL DEFAULT 'DE' CHECK (country_code ~ '^[A-Z]{2}$'),
    vat_id        text,
    tax_number    text,
    plan          text        NOT NULL DEFAULT 'TRIAL',
    version       integer     NOT NULL DEFAULT 0,       -- optimistic locking (ETag)
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE membership (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    user_id       uuid        NOT NULL REFERENCES app_user (id),
    role          text        NOT NULL CHECK (role IN ('OWNER', 'MEMBER', 'ADVISOR')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, user_id)
);
CREATE INDEX membership_workspace_idx ON membership (workspace_id);
CREATE INDEX membership_user_idx ON membership (user_id);

ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON membership
    USING (workspace_id = current_workspace_id() OR user_id = current_app_user_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE workspace ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace
    USING (id = current_workspace_id()
           OR id IN (SELECT m.workspace_id FROM membership m WHERE m.user_id = current_app_user_id()))
    WITH CHECK (id = current_workspace_id());

-- ---------------------------------------------------------------------------
-- Audit log (NF-10): append-only, one hash chain per workspace.
-- hash = SHA-256(prev_hash || canonical entry), see AuditEntry.canonical().
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    seq           bigint      NOT NULL CHECK (seq >= 1),
    at            timestamptz NOT NULL,
    actor         uuid,                                  -- app_user.id; NULL = system
    action        text        NOT NULL,
    payload       jsonb       NOT NULL DEFAULT '{}',
    prev_hash     bytea       NOT NULL,
    hash          bytea       NOT NULL,
    UNIQUE (workspace_id, seq)
);
CREATE INDEX audit_log_workspace_at_idx ON audit_log (workspace_id, at);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Nobody but the schema owner can change or remove audit rows (Software Design §7).
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM erika_app;

-- The daily chain check runs without a workspace and needs every workspace's ID, and
-- only the ID. SECURITY DEFINER runs as erika_owner (table owner, not subject to RLS).
CREATE FUNCTION all_workspace_ids() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT id FROM workspace ORDER BY id $$;
REVOKE ALL ON FUNCTION all_workspace_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION all_workspace_ids() TO erika_app;

-- ---------------------------------------------------------------------------
-- Spring Session (server-side sessions, ADR 0008). System tables: sessions exist
-- before a workspace is chosen. Schema as shipped with Spring Session JDBC.
-- ---------------------------------------------------------------------------
CREATE TABLE spring_session (
    primary_id             char(36)     NOT NULL,
    session_id             char(36)     NOT NULL,
    creation_time          bigint       NOT NULL,
    last_access_time       bigint       NOT NULL,
    max_inactive_interval  integer      NOT NULL,
    expiry_time            bigint       NOT NULL,
    principal_name         varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  char(36)      NOT NULL,
    attribute_name      varchar(200)  NOT NULL,
    attribute_bytes     bytea         NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
