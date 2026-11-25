-- MARA Stage 2 (ADR 0036, 0039): technicians, customer type, purchase prices, sites, staff
-- levels, checklist templates and work orders. Expand-only.

-- TECHNICIAN works only in the MARA app (ADR 0039).
ALTER TABLE membership DROP CONSTRAINT IF EXISTS membership_role_check;
ALTER TABLE membership ADD CONSTRAINT membership_role_check
    CHECK (role IN ('OWNER', 'MEMBER', 'ADVISOR', 'TECHNICIAN'));
ALTER TABLE invitation DROP CONSTRAINT IF EXISTS invitation_role_check;
ALTER TABLE invitation ADD CONSTRAINT invitation_role_check
    CHECK (role IN ('MEMBER', 'ADVISOR', 'TECHNICIAN'));

-- Customers (M-OF-01): business or private; leitweg_id existed as a reserved column (V7).
ALTER TABLE party ADD COLUMN customer_type text NOT NULL DEFAULT 'BUSINESS'
    CHECK (customer_type IN ('BUSINESS', 'PRIVATE'));

-- Articles (M-OF-04): optional purchase price.
ALTER TABLE article ADD COLUMN purchase_price numeric(15,4) CHECK (purchase_price >= 0);

-- Job sites: where the work is, per customer (M-OF-01). Archived instead of deleted.
CREATE TABLE site (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    customer_id   uuid        NOT NULL,                       -- creator's customer (party.id), checked by the module
    name          text        NOT NULL,
    street        text,
    postal_code   text,
    city          text,
    country_code  text        NOT NULL DEFAULT 'DE' CHECK (country_code ~ '^[A-Z]{2}$'),
    notes         text,
    archived      boolean     NOT NULL DEFAULT false,
    version       integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX site_workspace_customer_idx ON site (workspace_id, customer_id, lower(name));
ALTER TABLE site ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON site
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON site FROM erika_app;

-- Staff levels with hourly rates (M-OF-04): Meister, Geselle, Azubi …
CREATE TABLE staff_level (
    id            uuid          PRIMARY KEY,
    workspace_id  uuid          NOT NULL REFERENCES workspace (id),
    name          text          NOT NULL,
    hourly_rate   numeric(15,4) NOT NULL CHECK (hourly_rate >= 0),
    archived      boolean       NOT NULL DEFAULT false,
    version       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id)
);
CREATE INDEX staff_level_workspace_idx ON staff_level (workspace_id, lower(name));
ALTER TABLE staff_level ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_level
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON staff_level FROM erika_app;

-- Per person in a workspace: staff level and whether prices are shown in the app (ADR 0039).
-- Keyed by user; the membership itself belongs to identity.
CREATE TABLE staff_profile (
    workspace_id    uuid    NOT NULL REFERENCES workspace (id),
    user_id         uuid    NOT NULL,
    staff_level_id  uuid,
    can_see_prices  boolean NOT NULL DEFAULT false,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id),
    FOREIGN KEY (workspace_id, staff_level_id) REFERENCES staff_level (workspace_id, id)
);
ALTER TABLE staff_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_profile
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE TRUNCATE ON staff_profile FROM erika_app;

-- Checklist templates per job type (M-OF-05); items as JSON (key, label, kind, required).
CREATE TABLE checklist_template (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    name          text        NOT NULL,
    items         jsonb       NOT NULL,
    archived      boolean     NOT NULL DEFAULT false,
    version       integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX checklist_template_workspace_idx ON checklist_template (workspace_id, lower(name));
ALTER TABLE checklist_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checklist_template
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON checklist_template FROM erika_app;

-- Work orders (Aufträge, M-OF-02). Called work_order because "job" is the background job table.
-- The sync columns (field versions, change sequence) follow in MARA Stage 4 (ADR 0038).
CREATE TABLE work_order (
    id                     uuid        PRIMARY KEY,
    workspace_id           uuid        NOT NULL REFERENCES workspace (id),
    customer_id            uuid        NOT NULL,              -- creator's customer, checked by the module
    site_id                uuid        REFERENCES site (id),
    title                  text        NOT NULL,
    description            text,
    planned_from           timestamptz,
    planned_to             timestamptz,
    status                 text        NOT NULL DEFAULT 'PLANNED'
                                       CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DONE', 'SIGNED', 'INVOICED', 'CANCELLED')),
    checklist_template_id  uuid        REFERENCES checklist_template (id),
    created_by             uuid,
    version                integer     NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CHECK (planned_to IS NULL OR planned_from IS NULL OR planned_to >= planned_from),
    UNIQUE (workspace_id, id)
);
CREATE INDEX work_order_workspace_planned_idx ON work_order (workspace_id, planned_from);
CREATE INDEX work_order_workspace_customer_idx ON work_order (workspace_id, customer_id);
ALTER TABLE work_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON work_order FROM erika_app;

-- Who works on a work order (M-OF-02). Rows are replaced when the assignment changes.
CREATE TABLE work_order_assignment (
    workspace_id   uuid NOT NULL REFERENCES workspace (id),
    work_order_id  uuid NOT NULL,
    user_id        uuid NOT NULL,
    PRIMARY KEY (workspace_id, work_order_id, user_id),
    FOREIGN KEY (workspace_id, work_order_id) REFERENCES work_order (workspace_id, id)
);
CREATE INDEX work_order_assignment_user_idx ON work_order_assignment (workspace_id, user_id);
ALTER TABLE work_order_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order_assignment
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE TRUNCATE ON work_order_assignment FROM erika_app;
