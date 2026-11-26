-- MARA Stage 6 (M-MO-05, ADR 0040): photos of a report, owned by the `media` module. The record
-- syncs at once (state PENDING_FILE); the file follows through tus uploads into the uploads
-- bucket, is checked against the announced SHA-256, virus-scanned by the worker (fail closed)
-- and then lives in the media bucket with a thumbnail (STORED). Expand-only.

CREATE TABLE photo (
    id                  uuid        PRIMARY KEY,               -- made on the phone (UUIDv7)
    workspace_id        uuid        NOT NULL REFERENCES workspace (id),
    report_id           uuid        NOT NULL,                  -- reports' record, checked by the module
    author_id           uuid        NOT NULL,
    caption             text,
    category            text        NOT NULL DEFAULT 'OTHER' CHECK (category IN ('BEFORE', 'AFTER', 'DEFECT', 'OTHER')),
    checklist_item_key  text,                                  -- the checklist item this photo answers
    taken_at            timestamptz NOT NULL,
    size_bytes          bigint      NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 15728640),
    sha256              text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    state               text        NOT NULL DEFAULT 'PENDING_FILE'
                                    CHECK (state IN ('PENDING_FILE', 'UPLOADED', 'STORED', 'REJECTED')),
    reject_reason       text        CHECK (reject_reason IN ('VIRUS', 'NOT_AN_IMAGE')),
    upload_offset       bigint      NOT NULL DEFAULT 0 CHECK (upload_offset >= 0 AND upload_offset <= size_bytes),
    width               integer,
    height              integer,
    deleted_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX photo_report_idx ON photo (workspace_id, report_id);
ALTER TABLE photo ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON photo
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON photo FROM erika_app;
