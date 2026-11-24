-- Release 1.1 (build plan Stage 12, ADR 0035): IN-06 sender allow-list and quarantine,
-- AR-05 due-date reminders, CR-08 logo on the PDF, CR-09 recurring invoices, VW-07 English view.
-- Expand only. Every tenant table follows the tenancy rules of V2 (checked by TenancyDatabaseIT).

-- ---------------------------------------------------------------------------
-- intake: allow-list of senders (IN-06). Mail from other senders is archived as always, but its
-- attachments wait in quarantine until someone releases or discards it.
-- ---------------------------------------------------------------------------
CREATE TABLE intake_settings (
    workspace_id        uuid        PRIMARY KEY REFERENCES workspace (id),
    allow_list_enabled  boolean     NOT NULL DEFAULT false,
    updated_at          timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE intake_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON intake_settings
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- An address (rechnung@lieferant.de) or a whole domain (@lieferant.de).
CREATE TABLE intake_sender (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    pattern       text        NOT NULL CHECK (pattern ~ '^([^@\s]+@|@)[^@\s]+\.[^@\s]+$'),
    created_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, pattern)
);
CREATE INDEX intake_sender_workspace_idx ON intake_sender (workspace_id);
ALTER TABLE intake_sender ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON intake_sender
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE intake DROP CONSTRAINT intake_status_check;
ALTER TABLE intake ADD CONSTRAINT intake_status_check
    CHECK (status IN ('AWAITING_FILE', 'RECEIVED', 'PROCESSING', 'DONE', 'REJECTED', 'FAILED', 'QUARANTINED', 'DISCARDED'));
ALTER TABLE intake ADD COLUMN from_address text;       -- email: the From header (IN-06)
ALTER TABLE intake ADD COLUMN subject text;            -- email: the subject, shortened (quarantine list)
ALTER TABLE intake ADD COLUMN released_by uuid;        -- who released or discarded a quarantined email
ALTER TABLE intake ADD COLUMN released_at timestamptz;
CREATE INDEX intake_quarantine_idx ON intake (workspace_id, created_at DESC) WHERE status = 'QUARANTINED';

-- ---------------------------------------------------------------------------
-- documents: due-date reminders (AR-05) and the English invoice view (VW-07)
-- ---------------------------------------------------------------------------
ALTER TABLE membership ADD COLUMN notify_due_dates boolean NOT NULL DEFAULT true;

-- One reminder per document and due date, so a re-run sends nothing twice.
CREATE TABLE due_reminder (
    workspace_id  uuid        NOT NULL,
    document_id   uuid        NOT NULL,
    due_date      date        NOT NULL,
    sent_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, document_id, due_date),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);
ALTER TABLE due_reminder ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON due_reminder
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON due_reminder FROM erika_app;
CREATE INDEX parsed_invoice_due_idx ON parsed_invoice (workspace_id, due_date);

-- English view, built by the worker next to the German one. Documents read before 1.1 get it
-- when they are read again (revalidation); until then the German view is shown.
ALTER TABLE parsed_invoice ADD COLUMN view_en jsonb;

-- ---------------------------------------------------------------------------
-- creator: logo and accent colour for the ZUGFeRD PDF (CR-08), recurring invoices (CR-09)
-- ---------------------------------------------------------------------------
ALTER TABLE invoice_settings ADD COLUMN logo bytea CHECK (octet_length(logo) <= 524288);
ALTER TABLE invoice_settings ADD COLUMN logo_media_type text CHECK (logo_media_type IN ('image/png', 'image/jpeg'));
ALTER TABLE invoice_settings ADD COLUMN accent_color text CHECK (accent_color ~ '^#[0-9A-Fa-f]{6}$');

CREATE TABLE recurring_invoice (
    id              uuid        PRIMARY KEY,
    workspace_id    uuid        NOT NULL REFERENCES workspace (id),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    source_invoice_id uuid      NOT NULL,                      -- the draft or invoice it was made from
    content         jsonb       NOT NULL,                      -- the draft content to copy
    interval        text        NOT NULL CHECK (interval IN ('MONTHLY', 'QUARTERLY')),
    next_run        date        NOT NULL,
    active          boolean     NOT NULL DEFAULT true,
    last_run        date,
    last_draft_id   uuid,
    created_by      uuid,
    version         integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recurring_invoice_workspace_idx ON recurring_invoice (workspace_id, next_run) WHERE active;
ALTER TABLE recurring_invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recurring_invoice
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
