-- Stage 5: intake (upload, email) and documents (IN-01, IN-02, IN-04, IN-05, NF-04).
-- Software Design §4 (document, parsed_invoice, validation_result) and §5 Flow A.
-- Every table follows the tenancy rules of V2 (checked by TenancyDatabaseIT).

-- ---------------------------------------------------------------------------
-- documents module
-- ---------------------------------------------------------------------------

-- One row per received (later also issued) document. The original is in the archive
-- (stored_object); the same bytes are one document per workspace (ADR 0023).
CREATE TABLE document (
    id                   uuid        PRIMARY KEY,
    workspace_id         uuid        NOT NULL REFERENCES workspace (id),
    direction            text        NOT NULL CHECK (direction IN ('IN', 'OUT')),
    doc_type             text        NOT NULL DEFAULT 'UNKNOWN'
                                     CHECK (doc_type IN ('UNKNOWN', 'INVOICE', 'CREDIT_NOTE', 'OTHER')),
    format               text        NOT NULL DEFAULT 'UNKNOWN'
                                     CHECK (format IN ('UNKNOWN', 'XRECHNUNG_UBL', 'XRECHNUNG_CII', 'UBL', 'CII', 'ZUGFERD',
                                                       'PDF_ONLY', 'UNSUPPORTED', 'UNREADABLE')),
    format_version       text,                                -- detected profile, e.g. XRECHNUNG, EN16931
    not_einvoice_reason  text,
    sha256               text        NOT NULL,
    file_name            text,
    media_type           text        NOT NULL,
    size_bytes           bigint      NOT NULL CHECK (size_bytes >= 0),
    status               text        NOT NULL DEFAULT 'NEW'
                                     CHECK (status IN ('NEW', 'CHECKED', 'APPROVED', 'PAID', 'DISPUTED', 'BOOKED')),
    processing           text        NOT NULL DEFAULT 'PENDING' CHECK (processing IN ('PENDING', 'DONE', 'FAILED')),
    received_via         text        NOT NULL CHECK (received_via IN ('UPLOAD', 'EMAIL', 'CREATED')),
    duplicate_of         uuid,                                -- business duplicate (IN-05), an earlier document
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, sha256),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, sha256) REFERENCES stored_object (workspace_id, sha256),
    FOREIGN KEY (workspace_id, duplicate_of) REFERENCES document (workspace_id, id)
);
CREATE INDEX document_workspace_created_idx ON document (workspace_id, created_at DESC, id DESC);
CREATE INDEX document_workspace_status_idx ON document (workspace_id, status, processing);

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Documents are never deleted by the app (GoBD); deletion after retention is a separate job.
REVOKE DELETE, TRUNCATE ON document FROM erika_app;

-- Neutral invoice model (JSONB, without embedded attachment content) plus indexed columns.
CREATE TABLE parsed_invoice (
    document_id     uuid        PRIMARY KEY,
    workspace_id    uuid        NOT NULL,
    invoice_no      text,                                     -- BT-1
    issue_date      date,                                     -- BT-2
    due_date        date,                                     -- BT-9
    currency        text,                                     -- BT-5
    seller_name     text,                                     -- BT-27
    seller_tax_id   text,                                     -- BT-31, else BT-32 (duplicate check)
    buyer_name      text,                                     -- BT-44
    net             numeric,                                  -- BT-109
    tax             numeric,                                  -- BT-110
    gross           numeric,                                  -- BT-112
    data            jsonb       NOT NULL,
    parsed_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);
CREATE INDEX parsed_invoice_workspace_issue_idx ON parsed_invoice (workspace_id, issue_date);
-- Business duplicate check (IN-05): same supplier tax ID + invoice number + gross amount + currency.
CREATE INDEX parsed_invoice_duplicate_idx ON parsed_invoice (workspace_id, seller_tax_id, invoice_no, gross, currency);

ALTER TABLE parsed_invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON parsed_invoice
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- One row per validation run (BR-07: the pack that was applied). Results are history:
-- never changed or deleted by the app.
CREATE TABLE validation_result (
    id                uuid        PRIMARY KEY,
    workspace_id      uuid        NOT NULL,
    document_id       uuid        NOT NULL,
    job_id            uuid        UNIQUE,                     -- the job that produced it (idempotency)
    artefact_version  text        NOT NULL,                   -- validation pack ID
    level             text        NOT NULL CHECK (level IN ('VALID', 'WARNING', 'ERROR')),
    applicable        boolean     NOT NULL,
    scenario          text,
    messages          jsonb       NOT NULL,
    duration_ms       bigint,
    checked_at        timestamptz NOT NULL,
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id)
);
CREATE INDEX validation_result_document_idx ON validation_result (workspace_id, document_id, checked_at DESC);

ALTER TABLE validation_result ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON validation_result
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

REVOKE UPDATE, DELETE, TRUNCATE ON validation_result FROM erika_app;

-- ---------------------------------------------------------------------------
-- intake module
-- ---------------------------------------------------------------------------

-- The workspace's intake email address: <token>@<intake domain> (IN-01).
CREATE TABLE intake_address (
    workspace_id  uuid        PRIMARY KEY REFERENCES workspace (id),
    token         text        NOT NULL UNIQUE CHECK (token ~ '^[a-z0-9]{16,40}$'),
    created_at    timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE intake_address ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON intake_address
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Existing workspaces get an address; new ones get one when they are created.
-- gen_random_uuid() uses a cryptographically strong random source.
INSERT INTO intake_address (workspace_id, token)
SELECT id, substr(replace(gen_random_uuid()::text, '-', ''), 1, 20) FROM workspace;

-- Mail arrives without a workspace: the recipient's token decides which one. Only the ID
-- is returned, as erika_owner (not subject to RLS).
CREATE FUNCTION intake_workspace_for_token(p_token text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT workspace_id FROM intake_address WHERE token = lower(p_token) $$;
REVOKE ALL ON FUNCTION intake_workspace_for_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION intake_workspace_for_token(text) TO erika_app;

-- One upload or one received email.
CREATE TABLE intake (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    source         text        NOT NULL CHECK (source IN ('UPLOAD', 'EMAIL')),
    status         text        NOT NULL
                               CHECK (status IN ('AWAITING_FILE', 'RECEIVED', 'PROCESSING', 'DONE', 'REJECTED', 'FAILED')),
    reason         text,                                      -- why the whole intake was rejected
    file_name      text,                                      -- upload: the file's name
    declared_size  bigint,                                    -- upload: announced size
    content_type   text,
    upload_key     text,                                      -- upload: key in the uploads bucket
    raw_sha256     text,                                      -- email: the archived raw message
    sender         text,                                      -- email: envelope sender (IN-06, later)
    created_by     uuid,                                      -- upload: the user
    created_at     timestamptz NOT NULL DEFAULT now(),
    received_at    timestamptz,
    processed_at   timestamptz,
    UNIQUE (workspace_id, id)
);
CREATE INDEX intake_workspace_created_idx ON intake (workspace_id, created_at DESC);

ALTER TABLE intake ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON intake
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- One row per file found in an intake (the file itself, ZIP entries, email attachments).
CREATE TABLE intake_item (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL,
    intake_id     uuid        NOT NULL,
    seq           integer     NOT NULL,
    file_name     text        NOT NULL,
    size_bytes    bigint,
    sha256        text,
    outcome       text        NOT NULL CHECK (outcome IN ('IMPORTED', 'ALREADY_IN_ARCHIVE', 'REJECTED')),
    reason        text,
    document_id   uuid,
    UNIQUE (intake_id, seq),
    FOREIGN KEY (workspace_id, intake_id) REFERENCES intake (workspace_id, id)
);
CREATE INDEX intake_item_workspace_idx ON intake_item (workspace_id, intake_id);

ALTER TABLE intake_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON intake_item
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- ---------------------------------------------------------------------------
-- identity module: notification preference per member (expand-only).
-- ---------------------------------------------------------------------------
ALTER TABLE membership ADD COLUMN notify_new_documents boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- platform: monitoring of the job queue (alerts: intake older than 5 minutes, failed
-- jobs). Counts only, across all workspaces; the job table is a system table.
-- ---------------------------------------------------------------------------
CREATE INDEX job_queued_idx ON job (type, run_at) WHERE state = 'QUEUED';
