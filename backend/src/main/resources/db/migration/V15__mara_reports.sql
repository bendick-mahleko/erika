-- MARA Stage 5 (M-MO-03 to M-MO-08): job reports recorded on site, owned by the `reports`
-- module. A report belongs to a work order (checked by the module through workorders'
-- published API, no foreign key across modules). Its child records each have one author, so
-- offline edits rarely meet (design M4). Children are deleted softly (deleted_at): a delete
-- loses against a concurrent edit, which brings the record back (ADR 0038). Expand-only.

CREATE TABLE report (
    id                uuid        PRIMARY KEY,               -- derived from the work order (mara-rules/vectors/ids/)
    workspace_id      uuid        NOT NULL REFERENCES workspace (id),
    work_order_id     uuid        NOT NULL,
    version           integer     NOT NULL DEFAULT 1 CHECK (version >= 1),
    state             text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'COMPLETED')),
    work_description  text,
    completed_at      timestamptz,
    completed_by      uuid,
    created_by        uuid        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, work_order_id, version)
);
ALTER TABLE report ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON report FROM erika_app;

-- Times (M-MO-03): raw start and end as recorded, never rounded here (M-BR-04). ended_at is
-- null while the timer runs. technician_id is whose time it is (one phone may record a team).
CREATE TABLE time_entry (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    report_id      uuid        NOT NULL,
    author_id      uuid        NOT NULL,
    technician_id  uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('WORK', 'TRAVEL')),
    started_at     timestamptz NOT NULL,
    ended_at       timestamptz,
    note           text,
    deleted_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
CREATE INDEX time_entry_report_idx ON time_entry (workspace_id, report_id);
ALTER TABLE time_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON time_entry
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON time_entry FROM erika_app;

-- Materials (M-MO-04): from the article list or free text; prices come on the invoice (Stage 8).
CREATE TABLE material_line (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    report_id     uuid        NOT NULL,
    author_id     uuid        NOT NULL,
    article_id    uuid,                                       -- creator's article, checked by the module
    description   text        NOT NULL,
    quantity      numeric     NOT NULL CHECK (quantity > 0),
    unit_code     text        NOT NULL CHECK (unit_code ~ '^[A-Z0-9]{1,3}$'),
    deleted_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
CREATE INDEX material_line_report_idx ON material_line (workspace_id, report_id);
ALTER TABLE material_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON material_line
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON material_line FROM erika_app;

-- Aufmaß (M-MO-06): the inputs exactly as entered (numeric keeps their scale) and the result
-- the server calculated with the shared rules (rule_version, mara-rules/vectors/aufmass/).
CREATE TABLE aufmass_position (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    report_id     uuid        NOT NULL,
    author_id     uuid        NOT NULL,
    label         text        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('LENGTH', 'AREA', 'VOLUME', 'PIECE', 'FORMULA')),
    length        numeric,
    width         numeric,
    height        numeric,
    count         numeric,
    expression    text,
    deductions    jsonb       NOT NULL DEFAULT '[]',
    result        numeric     NOT NULL CHECK (result >= 0),
    rule_version  integer     NOT NULL,
    unit_code     text        NOT NULL CHECK (unit_code ~ '^[A-Z0-9]{1,3}$'),
    deleted_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
CREATE INDEX aufmass_position_report_idx ON aufmass_position (workspace_id, report_id);
ALTER TABLE aufmass_position ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aufmass_position
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON aufmass_position FROM erika_app;

-- Checklist answers (M-MO-07): one per report and item (the ID is derived from both, so two
-- phones answering offline write to the same record). Items come from the work order's template.
CREATE TABLE checklist_answer (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    report_id     uuid        NOT NULL,
    item_key      text        NOT NULL,
    value         text,
    author_id     uuid        NOT NULL,                       -- who answered last
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, report_id, item_key),
    FOREIGN KEY (workspace_id, report_id) REFERENCES report (workspace_id, id)
);
ALTER TABLE checklist_answer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checklist_answer
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON checklist_answer FROM erika_app;
