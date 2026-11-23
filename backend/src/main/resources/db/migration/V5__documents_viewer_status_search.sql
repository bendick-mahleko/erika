-- Stage 6: documents, viewer, status and search (VW-01, VW-02, VW-06, AR-03, AR-04).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        RAISE EXCEPTION 'Extension pg_trgm is missing. As a superuser run: CREATE EXTENSION pg_trgm SCHEMA erika; (see docs/dev-setup.md)';
    END IF;
END $$;

-- Metadata the user may change (AR-03). The original never changes.
ALTER TABLE document ADD COLUMN tags text[] NOT NULL DEFAULT '{}';
ALTER TABLE document ADD COLUMN note text;
CREATE INDEX document_tags_idx ON document USING gin (tags);

-- Status history: who changed what, when, with which note (AR-03). Append-only for the app.
CREATE TABLE status_event (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL,
    document_id   uuid        NOT NULL,
    from_status   text,
    to_status     text        NOT NULL,
    note          text,
    user_id       uuid,
    at            timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);
CREATE INDEX status_event_document_idx ON status_event (workspace_id, document_id, at);

ALTER TABLE status_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON status_event
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON status_event FROM erika_app;

-- The invoice view (InvoiceView from invoice-core, built by the worker) and search.
ALTER TABLE parsed_invoice ADD COLUMN view jsonb;
ALTER TABLE parsed_invoice ADD COLUMN search_text text;
ALTER TABLE parsed_invoice ADD COLUMN search tsvector
    GENERATED ALWAYS AS (to_tsvector('german', coalesce(search_text, ''))) STORED;
CREATE INDEX parsed_invoice_search_idx ON parsed_invoice USING gin (search);
CREATE INDEX parsed_invoice_seller_trgm_idx ON parsed_invoice USING gin (seller_name gin_trgm_ops);
CREATE INDEX parsed_invoice_invoice_no_idx ON parsed_invoice (workspace_id, invoice_no);

-- Objects derived from an original (PDF view copy, decoded attachments). They can always be
-- rebuilt from the original, so they live in a bucket without Object Lock.
CREATE TABLE derived_object (
    workspace_id  uuid        NOT NULL,
    document_id   uuid        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('VIEW_PDF', 'ATTACHMENT')),
    idx           integer     NOT NULL,
    storage_key   text        NOT NULL,
    sha256        text        NOT NULL,
    media_type    text        NOT NULL,
    file_name     text,
    size_bytes    bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, document_id, kind, idx),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);

ALTER TABLE derived_object ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON derived_object
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Documents read before Stage 6 have no view yet: read them again once.
INSERT INTO job (id, type, payload, workspace_id)
SELECT gen_random_uuid(), 'documents.parse-and-validate', jsonb_build_object('documentId', d.id), d.workspace_id
  FROM document d
 WHERE d.processing = 'DONE';
