-- MARA Stage 4 (ADR 0038): the sync engine's change feed, idempotency log and conflict notes,
-- work notes on work orders and push tokens on devices. Expand-only.

-- One change counter per workspace. A writing transaction takes its next number with
-- INSERT … ON CONFLICT DO UPDATE … RETURNING, which locks the row until commit: within a
-- workspace, sequence order is commit order, so a pull cursor never skips a late commit.
CREATE TABLE sync_counter (
    workspace_id  uuid    PRIMARY KEY REFERENCES workspace (id),
    value         bigint  NOT NULL CHECK (value >= 0),
    -- Records that existed before the first sync are entered into the change feed once, by
    -- the sync module (field hashes are computed in code), when the first phone syncs.
    bootstrapped  boolean NOT NULL DEFAULT false
);
ALTER TABLE sync_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sync_counter
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON sync_counter FROM erika_app;

-- The change feed: one row per syncable record, with the sequence number of its last change.
-- fields: {"field": [version, "sha-256 of the JSON value"]}; a field's version is the sequence
-- number of the change that last changed it. Only hashes, never values: no customer data here.
CREATE TABLE change_log (
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    entity        text        NOT NULL,
    entity_id     uuid        NOT NULL,
    seq           bigint      NOT NULL,
    fields        jsonb       NOT NULL DEFAULT '{}',
    changed_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, entity, entity_id)
);
CREATE INDEX change_log_workspace_seq_idx ON change_log (workspace_id, seq);
ALTER TABLE change_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_log
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON change_log FROM erika_app;

-- Mutations already applied, with their result: a resend after a timeout changes nothing and
-- gets the same answer. Kept 30 days.
CREATE TABLE applied_mutation (
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    mutation_id   uuid        NOT NULL,
    device_id     uuid        NOT NULL,
    result        jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, mutation_id)
);
CREATE INDEX applied_mutation_workspace_device_idx ON applied_mutation (workspace_id, device_id, created_at);
ALTER TABLE applied_mutation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON applied_mutation
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE TRUNCATE ON applied_mutation FROM erika_app;

-- Concurrent changes to the same field, for the office (customer data: last writer wins
-- with a note; work orders: the loser's value is kept here). Values as JSON text.
CREATE TABLE sync_conflict (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    entity         text        NOT NULL,
    entity_id      uuid        NOT NULL,
    field          text        NOT NULL,
    server_value   text,
    device_value   text,
    winner         text        NOT NULL CHECK (winner IN ('SERVER', 'DEVICE')),
    user_id        uuid        NOT NULL,
    device_id      uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sync_conflict_workspace_entity_idx ON sync_conflict (workspace_id, entity_id, created_at);
ALTER TABLE sync_conflict ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sync_conflict
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON sync_conflict FROM erika_app;

-- The customer's phone number: the technician calls from the work order (M-MO-01).
ALTER TABLE party ADD COLUMN phone text;

-- Work notes of the technicians (M-MO-01): the technician wins on conflicts.
ALTER TABLE work_order ADD COLUMN work_notes text;

-- Push token of the app for data-only "sync now" messages (FCM; no personal data in them).
ALTER TABLE mobile_device ADD COLUMN push_token text;
ALTER TABLE mobile_device ADD COLUMN push_token_updated_at timestamptz;
