-- MARA Stage 8 (M-IV-01 to M-IV-07, M-BR-04, M-BR-05): invoices from signed reports. Draft
-- sources and reminders belong to `creator`; the billing settings and the report-invoice links to
-- `reportinvoicing`. Expand-only.

-- ---- creator --------------------------------------------------------------------------------

-- Where a draft came from (e.g. a MARA report): one source can be on one draft only, so a report
-- is never billed twice. Removed with the draft while it is still a draft.
CREATE TABLE invoice_draft_source (
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    draft_id       uuid        NOT NULL REFERENCES invoice_draft (id) ON DELETE CASCADE,
    source_module  text        NOT NULL,
    source_id      uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, source_module, source_id)
);
CREATE INDEX invoice_draft_source_draft_idx ON invoice_draft_source (workspace_id, draft_id);
ALTER TABLE invoice_draft_source ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_draft_source
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Payment reminders for overdue outgoing invoices (M-IV-06): each one is an email of the invoice
-- with a reminder text (an `invoice_delivery`), level 1 = Zahlungserinnerung, 2+ = Mahnung.
CREATE TABLE invoice_reminder (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    invoice_id   uuid        NOT NULL,
    level        integer     NOT NULL CHECK (level BETWEEN 1 AND 3),
    recipient    text        NOT NULL,
    delivery_id  uuid        NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invoice_reminder_invoice_idx ON invoice_reminder (workspace_id, invoice_id);
ALTER TABLE invoice_reminder ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_reminder
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON invoice_reminder FROM erika_app;

-- ---- reportinvoicing ------------------------------------------------------------------------

-- How the firm bills reports (M-BR-04, M-BR-05): times rounded up per entry to the billing unit,
-- a fallback hourly rate, and travel as nothing, a flat amount per visit or by time.
CREATE TABLE report_invoicing_settings (
    workspace_id          uuid          PRIMARY KEY REFERENCES workspace (id),
    billing_unit_minutes  integer       NOT NULL DEFAULT 15 CHECK (billing_unit_minutes IN (1, 5, 6, 10, 15, 30, 60)),
    default_hourly_rate   numeric(15,4) CHECK (default_hourly_rate >= 0),
    travel_mode           text          NOT NULL DEFAULT 'TIME' CHECK (travel_mode IN ('NONE', 'FLAT', 'TIME')),
    travel_flat_amount    numeric(15,4) CHECK (travel_flat_amount >= 0),
    travel_hourly_rate    numeric(15,4) CHECK (travel_hourly_rate >= 0),
    updated_at            timestamptz   NOT NULL DEFAULT now()
);
ALTER TABLE report_invoicing_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_invoicing_settings
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Per customer, the same settings may differ (null: the firm's).
CREATE TABLE report_invoicing_customer (
    workspace_id          uuid          NOT NULL REFERENCES workspace (id),
    customer_id           uuid          NOT NULL,               -- creator's customer, checked by the module
    billing_unit_minutes  integer       CHECK (billing_unit_minutes IN (1, 5, 6, 10, 15, 30, 60)),
    travel_mode           text          CHECK (travel_mode IN ('NONE', 'FLAT', 'TIME')),
    travel_flat_amount    numeric(15,4) CHECK (travel_flat_amount >= 0),
    travel_hourly_rate    numeric(15,4) CHECK (travel_hourly_rate >= 0),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, customer_id)
);
ALTER TABLE report_invoicing_customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_invoicing_customer
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Which invoice billed which report version (M-IV-01); the work order is INVOICED once issued.
CREATE TABLE report_invoice_link (
    workspace_id   uuid        NOT NULL REFERENCES workspace (id),
    report_id      uuid        NOT NULL,
    work_order_id  uuid        NOT NULL,
    invoice_id     uuid        NOT NULL,
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, report_id)
);
CREATE INDEX report_invoice_link_invoice_idx ON report_invoice_link (workspace_id, invoice_id);
ALTER TABLE report_invoice_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_invoice_link
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, TRUNCATE ON report_invoice_link FROM erika_app;
