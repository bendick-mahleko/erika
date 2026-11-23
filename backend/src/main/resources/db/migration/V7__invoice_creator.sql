-- Stage 8: invoice creator (CR-01 … CR-07, BR-04, BR-06), ADR 0031.
-- Every table follows the tenancy rules of V2 (checked by TenancyDatabaseIT).

-- ---------------------------------------------------------------------------
-- platform: idempotency keys for POST requests that create or send something
-- (Software Design §6). One row per workspace and key; a repeated request returns the
-- resource the first one created.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_key (
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    key           text        NOT NULL CHECK (length(key) BETWEEN 1 AND 200),
    operation     text        NOT NULL,
    resource_id   uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, key)
);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- ---------------------------------------------------------------------------
-- documents: statuses of outgoing invoices (build plan, gap 4). Expand only: the
-- incoming statuses stay as they are.
-- ---------------------------------------------------------------------------
ALTER TABLE document DROP CONSTRAINT document_status_check;
ALTER TABLE document ADD CONSTRAINT document_status_check
    CHECK (status IN ('NEW', 'CHECKED', 'APPROVED', 'PAID', 'DISPUTED', 'BOOKED',
                      'ISSUED', 'SENT', 'DELIVERY_FAILED', 'CANCELLED'));

-- ---------------------------------------------------------------------------
-- creator module
-- ---------------------------------------------------------------------------

-- Seller data for invoices beyond the workspace profile (name, address, VAT ID and tax
-- number stay in `workspace`), numbering and defaults.
CREATE TABLE invoice_settings (
    workspace_id         uuid        PRIMARY KEY REFERENCES workspace (id),
    contact_name         text,                                -- BT-41
    contact_phone        text,                                -- BT-42
    contact_email        text,                                -- BT-43, and BT-34 (electronic address)
    legal_registration   text,                                -- BT-30, e.g. "HRB 12345, Amtsgericht Berlin"
    iban                 text,                                -- BT-84
    bic                  text,                                -- BT-86
    account_holder       text,                                -- BT-85
    kleinunternehmer     boolean     NOT NULL DEFAULT false,  -- BR-06: VAT category E with the § 19 UStG note
    number_pattern       text        NOT NULL DEFAULT 'RE-{YYYY}-{NNNN}',
    credit_note_pattern  text        NOT NULL DEFAULT 'GS-{YYYY}-{NNNN}',
    default_format       text        NOT NULL DEFAULT 'ZUGFERD_EN16931'
                                     CHECK (default_format IN ('XRECHNUNG_CII', 'XRECHNUNG_UBL', 'ZUGFERD_EN16931')),
    payment_terms_days   integer     NOT NULL DEFAULT 14 CHECK (payment_terms_days BETWEEN 0 AND 365),
    version              integer     NOT NULL DEFAULT 0,
    updated_at           timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE invoice_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_settings
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON invoice_settings FROM erika_app;

-- Customers (CR-05). Archived instead of deleted: issued invoices keep their own copy.
CREATE TABLE party (
    id                  uuid        PRIMARY KEY,
    workspace_id        uuid        NOT NULL REFERENCES workspace (id),
    customer_number     text,                                 -- BT-46
    name                text        NOT NULL,                 -- BT-44
    street              text,
    additional_line     text,
    postal_code         text,
    city                text,
    country_code        text        NOT NULL DEFAULT 'DE' CHECK (country_code ~ '^[A-Z]{2}$'),
    vat_id              text,                                 -- BT-48
    email               text,                                 -- BT-49 and the address for sending
    contact_name        text,
    buyer_reference     text,                                 -- BT-10 (XRechnung: mandatory)
    leitweg_id          text,                                 -- reserved for Release 2 (CR-10, B2G)
    vat_treatment       text        NOT NULL DEFAULT 'DOMESTIC'
                                    CHECK (vat_treatment IN ('DOMESTIC', 'REVERSE_CHARGE', 'INTRA_EU')),
    preferred_format    text        CHECK (preferred_format IN ('XRECHNUNG_CII', 'XRECHNUNG_UBL', 'ZUGFERD_EN16931')),
    payment_terms_days  integer     CHECK (payment_terms_days BETWEEN 0 AND 365),
    archived            boolean     NOT NULL DEFAULT false,
    version             integer     NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, customer_number)
);
CREATE INDEX party_workspace_name_idx ON party (workspace_id, lower(name));

ALTER TABLE party ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON party FROM erika_app;

-- Articles (CR-05): unit as UN/ECE Rec 20 code, net price, default VAT rate.
CREATE TABLE article (
    id              uuid          PRIMARY KEY,
    workspace_id    uuid          NOT NULL REFERENCES workspace (id),
    article_number  text,                                     -- BT-155
    name            text          NOT NULL,                   -- BT-153
    description     text,                                     -- BT-154
    unit_code       text          NOT NULL DEFAULT 'C62' CHECK (unit_code ~ '^[A-Z0-9]{1,3}$'),
    net_price       numeric(15,4) NOT NULL CHECK (net_price >= 0),
    vat_rate        text          NOT NULL DEFAULT 'STANDARD_19' CHECK (vat_rate IN ('STANDARD_19', 'REDUCED_7', 'ZERO')),
    archived        boolean       NOT NULL DEFAULT false,
    version         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, article_number)
);
CREATE INDEX article_workspace_name_idx ON article (workspace_id, lower(name));

ALTER TABLE article ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON article FROM erika_app;

-- Invoice numbering (CR-03, BR-04): one counter per workspace and sequence key (the pattern
-- with its date parts filled in, e.g. "RE-2026-{N}"). Locked with SELECT … FOR UPDATE while
-- an invoice is issued; a failed issue rolls the counter back, so numbers have no gaps.
CREATE TABLE number_sequence (
    workspace_id  uuid    NOT NULL REFERENCES workspace (id),
    sequence_key  text    NOT NULL,
    next_value    bigint  NOT NULL CHECK (next_value >= 1),
    PRIMARY KEY (workspace_id, sequence_key)
);

ALTER TABLE number_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON number_sequence
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON number_sequence FROM erika_app;

-- Issued invoices, credit notes and cancellations (the log of used numbers, BR-04). The file
-- itself is the document's original in the archive; this row never changes after issue.
CREATE TABLE invoice (
    id                   uuid          PRIMARY KEY,           -- = the draft's ID
    workspace_id         uuid          NOT NULL REFERENCES workspace (id),
    number               text          NOT NULL,              -- BT-1
    kind                 text          NOT NULL CHECK (kind IN ('INVOICE', 'CREDIT_NOTE', 'CANCELLATION', 'CORRECTION')),
    type_code            text          NOT NULL,              -- BT-3: 380, 381, 384
    format               text          NOT NULL CHECK (format IN ('XRECHNUNG_CII', 'XRECHNUNG_UBL', 'ZUGFERD_EN16931')),
    document_id          uuid          NOT NULL,
    sha256               text          NOT NULL,              -- the archived file (the document's original)
    file_name            text          NOT NULL,
    media_type           text          NOT NULL,
    original_invoice_id  uuid,                                -- BT-25 for credit notes, cancellations, corrections
    customer_id          uuid,
    issue_date           date          NOT NULL,
    due_date             date,
    buyer_name           text          NOT NULL,
    buyer_email          text,
    currency             text          NOT NULL,
    net                  numeric(15,2) NOT NULL,
    tax                  numeric(15,2) NOT NULL,
    gross                numeric(15,2) NOT NULL,
    issued_by            uuid,
    issued_at            timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, number),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, document_id),
    FOREIGN KEY (workspace_id, document_id) REFERENCES document (workspace_id, id),
    FOREIGN KEY (workspace_id, original_invoice_id) REFERENCES invoice (workspace_id, id)
);
CREATE INDEX invoice_workspace_issued_idx ON invoice (workspace_id, issued_at DESC, id DESC);

ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON invoice FROM erika_app;

-- Drafts (CR-01): editable until issued, then frozen. `data` holds the form content.
CREATE TABLE invoice_draft (
    id                   uuid        PRIMARY KEY,
    workspace_id         uuid        NOT NULL REFERENCES workspace (id),
    kind                 text        NOT NULL DEFAULT 'INVOICE'
                                     CHECK (kind IN ('INVOICE', 'CREDIT_NOTE', 'CANCELLATION', 'CORRECTION')),
    state                text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'ISSUING', 'ISSUED')),
    original_invoice_id  uuid,
    data                 jsonb       NOT NULL,
    issue_error          jsonb,                               -- validation messages of the last failed issue
    version              integer     NOT NULL DEFAULT 0,
    created_by           uuid,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, original_invoice_id) REFERENCES invoice (workspace_id, id)
);
CREATE INDEX invoice_draft_workspace_idx ON invoice_draft (workspace_id, state, updated_at DESC);

ALTER TABLE invoice_draft ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_draft
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
-- Unissued drafts may be discarded; issued ones are protected by the trigger below.
REVOKE TRUNCATE ON invoice_draft FROM erika_app;

CREATE FUNCTION invoice_draft_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'ISSUED' THEN
        RAISE EXCEPTION 'invoice draft % is issued and cannot be changed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER invoice_draft_frozen BEFORE UPDATE OR DELETE ON invoice_draft
    FOR EACH ROW EXECUTE FUNCTION invoice_draft_frozen();

-- Sending by email (CR-07): one row per send; the sent message (.eml, with the invoice
-- attached) is archived write-once next to the invoice file.
CREATE TABLE invoice_delivery (
    id                    uuid        PRIMARY KEY,
    workspace_id          uuid        NOT NULL REFERENCES workspace (id),
    invoice_id            uuid        NOT NULL,
    recipient             text        NOT NULL,
    subject               text        NOT NULL,
    message               text,
    status                text        NOT NULL DEFAULT 'QUEUED'
                                      CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED')),
    message_id            text        NOT NULL UNIQUE,        -- our RFC 5322 Message-ID
    provider_message_id   text,                               -- from the provider's SMTP answer
    eml_sha256            text,
    detail                text,                               -- provider's reason for a bounce (no invoice content)
    requested_by          uuid,
    created_at            timestamptz NOT NULL DEFAULT now(),
    sent_at               timestamptz,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, invoice_id) REFERENCES invoice (workspace_id, id),
    FOREIGN KEY (workspace_id, eml_sha256) REFERENCES stored_object (workspace_id, sha256)
);
CREATE INDEX invoice_delivery_workspace_idx ON invoice_delivery (workspace_id, invoice_id, created_at);
CREATE INDEX invoice_delivery_provider_idx ON invoice_delivery (provider_message_id);

ALTER TABLE invoice_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_delivery
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON invoice_delivery FROM erika_app;

-- Delivery webhooks (/webhooks/mail) arrive without a workspace: the message ID decides.
-- Only the IDs are returned, as erika_owner (not subject to RLS).
CREATE FUNCTION mail_delivery_for_message(p_message_id text)
    RETURNS TABLE (workspace_id uuid, delivery_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT workspace_id, id FROM invoice_delivery
           WHERE message_id = p_message_id OR provider_message_id = p_message_id
           LIMIT 1 $$;
REVOKE ALL ON FUNCTION mail_delivery_for_message(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mail_delivery_for_message(text) TO erika_app;
