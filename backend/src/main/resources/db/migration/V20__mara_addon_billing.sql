-- MARA Stage 10 (M-AC-02, ADR 0043): add-ons on the ERIKA subscription. One row per workspace and
-- add-on once it was started (trial) or booked. The Stripe subscription items of the add-on and
-- of its extra seats are kept so the worker can change their quantity. Expand-only.
CREATE TABLE billing_addon (
    workspace_id        uuid        NOT NULL REFERENCES workspace (id),
    addon               text        NOT NULL CHECK (addon IN ('MARA')),
    trial_ends_at       timestamptz,
    -- Free without Stripe (beta firms, docs/ops/beta.md).
    exempt              boolean     NOT NULL DEFAULT false,
    -- The subscription carries the add-on (confirmed by Stripe's webhook).
    active              boolean     NOT NULL DEFAULT false,
    -- BOOKING or CANCELLING while the worker and Stripe are on it.
    pending             text        CHECK (pending IN ('BOOKING', 'CANCELLING')),
    item_id             text,
    seat_item_id        text,
    seats_billed        integer     NOT NULL DEFAULT 0 CHECK (seats_billed >= 0),
    -- M-BR-09: the owner confirmed the works-council notice and the employee information.
    notice_accepted_at  timestamptz,
    notice_accepted_by  uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, addon)
);
ALTER TABLE billing_addon ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_addon
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
