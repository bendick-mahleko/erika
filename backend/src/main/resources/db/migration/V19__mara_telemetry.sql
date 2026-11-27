-- MARA Stage 9 (field test): content-free counters per device and day, sent by the app
-- (sync and upload outcomes, no record IDs or texts). A resend replaces the day. Kept 180 days
-- (deleted on ingest). Expand-only.
CREATE TABLE mobile_telemetry_day (
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    device_id     uuid        NOT NULL,
    day           date        NOT NULL,
    counters      jsonb       NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, device_id, day)
);
CREATE INDEX mobile_telemetry_day_day_idx ON mobile_telemetry_day (workspace_id, day);
ALTER TABLE mobile_telemetry_day ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mobile_telemetry_day
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
