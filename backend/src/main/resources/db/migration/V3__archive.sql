-- Stage 4: archive (AR-01, AR-02, BR-02, BR-03). One row per original in object storage.
-- The original is written once, under ws/<workspace>/<sha256>, with S3 Object Lock
-- (compliance mode) until retention_until. The row names the exact object version, so a
-- later write to the same key can never change what is served.

CREATE TABLE stored_object (
    workspace_id      uuid        NOT NULL REFERENCES workspace (id),
    sha256            text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes        bigint      NOT NULL CHECK (size_bytes >= 0),
    media_type        text        NOT NULL,
    storage_key       text        NOT NULL,
    version_id        text,                               -- S3 object version (NULL if the store has no versioning)
    stored_at         timestamptz NOT NULL DEFAULT now(),
    retention_until   date        NOT NULL,
    object_lock       text        NOT NULL CHECK (object_lock IN ('COMPLIANCE', 'NONE')),
    legal_hold        boolean     NOT NULL DEFAULT false,
    last_verified_at  timestamptz,
    PRIMARY KEY (workspace_id, sha256)                    -- never shared across tenants
);
CREATE INDEX stored_object_workspace_idx ON stored_object (workspace_id, stored_at);
CREATE INDEX stored_object_retention_idx ON stored_object (retention_until) WHERE NOT legal_hold;

ALTER TABLE stored_object ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stored_object
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Originals can never be deleted, and their identity never changed, by the app role.
-- Only these columns may change: legal hold, verification time, and the retention date
-- (only later, see trigger).
REVOKE UPDATE, DELETE, TRUNCATE ON stored_object FROM erika_app;
GRANT UPDATE (legal_hold, last_verified_at, retention_until) ON stored_object TO erika_app;

CREATE FUNCTION stored_object_retention_only_extends() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.retention_until < OLD.retention_until THEN
        RAISE EXCEPTION 'retention_until may only be extended (% -> %)', OLD.retention_until, NEW.retention_until
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER stored_object_retention_only_extends
    BEFORE UPDATE OF retention_until ON stored_object
    FOR EACH ROW EXECUTE FUNCTION stored_object_retention_only_extends();

-- The integrity check job runs without a workspace and needs a sample across all
-- workspaces: only the identifying columns, as erika_owner (not subject to RLS).
CREATE FUNCTION stored_object_sample(fraction double precision)
    RETURNS TABLE (workspace_id uuid, sha256 text)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT s.workspace_id, s.sha256 FROM stored_object s
         WHERE fraction >= 1.0 OR random() < fraction
         ORDER BY s.workspace_id, s.sha256
    $$;
REVOKE ALL ON FUNCTION stored_object_sample(double precision) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stored_object_sample(double precision) TO erika_app;
