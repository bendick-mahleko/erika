-- MARA Stage 3 (ADR 0037): registered phones of technicians and their bearer tokens. Expand-only.

-- One row per signed-in phone. Never deleted: revocation sets revoked_at, and the row stays
-- for the owner's device list and the audit trail.
CREATE TABLE mobile_device (
    id              uuid        PRIMARY KEY,
    workspace_id    uuid        NOT NULL REFERENCES workspace (id),
    user_id         uuid        NOT NULL REFERENCES app_user (id),
    name            text        NOT NULL,                     -- given by the app, e.g. "Pixel 7"
    platform        text        NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    app_version     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_sync_at    timestamptz,                              -- set by the sync engine (Stage 4)
    revoked_at      timestamptz,
    revoked_by      uuid,                                     -- app_user.id; NULL = the system (token reuse)
    revoked_reason  text        CHECK (revoked_reason IN ('OWNER', 'TOKEN_REUSE', 'SIGNED_OUT')),
    CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX mobile_device_workspace_user_idx ON mobile_device (workspace_id, user_id);
ALTER TABLE mobile_device ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mobile_device
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON mobile_device FROM erika_app;

-- Access tokens (15 minutes) and refresh tokens (rotating; one family per device). Only the
-- SHA-256 of the random token is stored. A refresh token is used once (used_at); presenting
-- it again revokes the device.
CREATE TABLE mobile_token (
    token_hash    bytea       PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    device_id     uuid        NOT NULL REFERENCES mobile_device (id),
    kind          text        NOT NULL CHECK (kind IN ('ACCESS', 'REFRESH')),
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (kind = 'REFRESH' OR used_at IS NULL)
);
CREATE INDEX mobile_token_workspace_device_idx ON mobile_token (workspace_id, device_id);
ALTER TABLE mobile_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mobile_token
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- A bearer request arrives without a workspace: the token decides which one. Only the ID is
-- returned, as erika_owner (not subject to RLS); everything else is read with RLS in place.
CREATE FUNCTION mobile_token_workspace(p_hash bytea) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT workspace_id FROM mobile_token WHERE token_hash = p_hash $$;
REVOKE ALL ON FUNCTION mobile_token_workspace(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mobile_token_workspace(bytea) TO erika_app;
