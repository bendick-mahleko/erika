-- MARA Stage 7 (M-MO-09 to M-MO-11, M-BR-01 to M-BR-03, M-BR-08): signatures, immutability and
-- the report PDF. A signed (or "sent unsigned") report version never changes: triggers refuse
-- every change to it and its records, even for the app role through plain SQL; corrections are a
-- new version. Expand-only.

ALTER TABLE report DROP CONSTRAINT IF EXISTS report_state_check;
ALTER TABLE report ADD CONSTRAINT report_state_check CHECK (state IN ('OPEN', 'COMPLETED', 'SIGNED', 'NOT_SIGNED'));
-- The version a correction is based on (M-BR-01).
ALTER TABLE report ADD COLUMN based_on uuid;

-- The signature (or the reason it is missing), as the phone recorded it: the customer's strokes
-- and image, the printed name and the SHA-256 of the canonical report the customer saw
-- (mara-rules/vectors/report/canonical.json). One per report version.
CREATE TABLE report_signature (
    id                 uuid        PRIMARY KEY,               -- derived from the report (ids/derived.json)
    workspace_id       uuid        NOT NULL REFERENCES workspace (id),
    report_id          uuid        NOT NULL,
    kind               text        NOT NULL CHECK (kind IN ('SIGNED', 'NOT_SIGNED')),
    content_hash       text        NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    signer_name        text,
    signed_at          timestamptz NOT NULL,                  -- the phone's clock, as shown to the customer
    comment            text,
    not_signed_reason  text,
    strokes            jsonb,
    signature_png      bytea,
    verification_code  text        NOT NULL CHECK (verification_code ~ '^[A-Z2-7]{10}$'),
    download_key_hash  bytea       NOT NULL,                  -- SHA-256 of the key in the QR code
    customer_email     text,
    created_by         uuid        NOT NULL,
    device_id          uuid,
    received_at        timestamptz NOT NULL DEFAULT now(),
    -- Set once by the worker: the archived master PDF (archive.Archive).
    pdf_sha256         text,
    pdf_archived_at    timestamptz,
    CHECK (kind = 'NOT_SIGNED' OR (signer_name IS NOT NULL AND signature_png IS NOT NULL)),
    CHECK (kind = 'SIGNED' OR not_signed_reason IS NOT NULL),
    UNIQUE (workspace_id, report_id),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
-- Codes are looked up without a workspace (public verification page), so they are unique overall.
CREATE UNIQUE INDEX report_signature_code_idx ON report_signature (verification_code);
ALTER TABLE report_signature ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_signature
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON report_signature FROM erika_app;

-- Every email of a report PDF to the customer, with the archived message (M-MO-11: logged).
CREATE TABLE report_delivery (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    report_id      uuid        NOT NULL,
    recipient      text        NOT NULL,
    eml_sha256     text        NOT NULL,
    provider_id    text,
    sent_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
CREATE INDEX report_delivery_report_idx ON report_delivery (workspace_id, report_id);
ALTER TABLE report_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_delivery
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON report_delivery FROM erika_app;

-- Photos copied into a correction share the original's files (MARA Stage 7).
ALTER TABLE photo ADD COLUMN file_photo_id uuid;

-- ---- immutability (M-MO-10, M-BR-01) ---------------------------------------------------------

CREATE FUNCTION report_locked(p_report uuid) RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = erika, pg_temp
    AS $$ SELECT EXISTS (SELECT 1 FROM report WHERE id = p_report AND state IN ('SIGNED', 'NOT_SIGNED')) $$;

-- A signed report row never changes again.
CREATE FUNCTION report_frozen() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = erika, pg_temp
    AS $$
BEGIN
    IF OLD.state IN ('SIGNED', 'NOT_SIGNED') THEN
        RAISE EXCEPTION 'report % is signed and does not change', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER report_frozen BEFORE UPDATE ON report FOR EACH ROW EXECUTE FUNCTION report_frozen();

-- Nothing is added to, changed in or removed from a signed report's records.
CREATE FUNCTION report_child_frozen() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = erika, pg_temp
    AS $$
BEGIN
    IF (TG_OP <> 'INSERT' AND report_locked(OLD.report_id)) OR (TG_OP <> 'DELETE' AND report_locked(NEW.report_id)) THEN
        RAISE EXCEPTION '% of a signed report does not change', TG_TABLE_NAME USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER time_entry_frozen BEFORE INSERT OR UPDATE OR DELETE ON time_entry
    FOR EACH ROW EXECUTE FUNCTION report_child_frozen();
CREATE TRIGGER material_line_frozen BEFORE INSERT OR UPDATE OR DELETE ON material_line
    FOR EACH ROW EXECUTE FUNCTION report_child_frozen();
CREATE TRIGGER aufmass_position_frozen BEFORE INSERT OR UPDATE OR DELETE ON aufmass_position
    FOR EACH ROW EXECUTE FUNCTION report_child_frozen();
CREATE TRIGGER checklist_answer_frozen BEFORE INSERT OR UPDATE OR DELETE ON checklist_answer
    FOR EACH ROW EXECUTE FUNCTION report_child_frozen();

-- Photos of a signed report: their file may still arrive (state, offset, size), but what the
-- customer signed (report, caption, category, checklist item, deletion, hash) is fixed.
CREATE FUNCTION photo_frozen() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = erika, pg_temp
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF report_locked(NEW.report_id) THEN
            RAISE EXCEPTION 'photo of a signed report does not change' USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF TG_OP = 'DELETE' OR (report_locked(OLD.report_id) AND (
            NEW.report_id IS DISTINCT FROM OLD.report_id OR NEW.caption IS DISTINCT FROM OLD.caption
            OR NEW.category IS DISTINCT FROM OLD.category OR NEW.checklist_item_key IS DISTINCT FROM OLD.checklist_item_key
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at OR NEW.sha256 IS DISTINCT FROM OLD.sha256
            OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes)) THEN
        RAISE EXCEPTION 'photo of a signed report does not change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER photo_frozen BEFORE INSERT OR UPDATE OR DELETE ON photo FOR EACH ROW EXECUTE FUNCTION photo_frozen();

-- A signature is written once; the worker only adds the archived PDF, once.
CREATE FUNCTION report_signature_frozen() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = erika, pg_temp
    AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.pdf_sha256 IS NOT NULL
       OR (to_jsonb(NEW) - 'pdf_sha256' - 'pdf_archived_at') IS DISTINCT FROM (to_jsonb(OLD) - 'pdf_sha256' - 'pdf_archived_at') THEN
        RAISE EXCEPTION 'a signature does not change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER report_signature_frozen BEFORE UPDATE OR DELETE ON report_signature
    FOR EACH ROW EXECUTE FUNCTION report_signature_frozen();

-- ---- public verification (M-MO-11) -----------------------------------------------------------

-- The verification page knows only the code: hash and status, never content. SECURITY DEFINER
-- because the code is looked up before a workspace is known.
CREATE FUNCTION report_verification(p_code text)
    RETURNS TABLE (workspace_id uuid, report_id uuid, kind text, content_hash text, signed_at timestamptz,
                   report_version integer, superseded boolean, pdf_sha256 text, download_key_hash bytea, firm_name text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT s.workspace_id, s.report_id, s.kind, s.content_hash, s.signed_at, r.version,
               EXISTS (SELECT 1 FROM report n WHERE n.workspace_id = r.workspace_id AND n.work_order_id = r.work_order_id
                                              AND n.version > r.version),
               s.pdf_sha256, s.download_key_hash, w.name
          FROM report_signature s
          JOIN report r ON r.workspace_id = s.workspace_id AND r.id = s.report_id
          JOIN workspace w ON w.id = s.workspace_id
         WHERE s.verification_code = p_code
    $$;
REVOKE ALL ON FUNCTION report_verification(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION report_verification(text) TO erika_app;
