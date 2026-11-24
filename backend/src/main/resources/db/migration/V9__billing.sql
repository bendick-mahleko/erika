-- Stage 10: billing with Stripe (EX-03), ADR 0033.
-- Every tenant table follows the tenancy rules of V2 (checked by TenancyDatabaseIT).

-- ---------------------------------------------------------------------------
-- billing: one account per workspace, mirrored from Stripe webhooks. The trial is ERIKA's own
-- (it starts at sign-up, without a card); a Stripe subscription takes over from there.
-- ---------------------------------------------------------------------------
CREATE TABLE billing_account (
    workspace_id          uuid        PRIMARY KEY REFERENCES workspace (id),
    trial_ends_at         timestamptz NOT NULL,
    exempt                boolean     NOT NULL DEFAULT false,   -- e.g. ERIKA's own workspace
    stripe_customer_id    text        UNIQUE,
    subscription_id       text,
    subscription_status   text        CHECK (subscription_status IN ('incomplete', 'incomplete_expired', 'trialing', 'active',
                                                                     'past_due', 'canceled', 'unpaid', 'paused')),
    price_id              text,
    plan_interval         text        CHECK (plan_interval IN ('month', 'year')),
    current_period_end    timestamptz,
    cancel_at_period_end  boolean     NOT NULL DEFAULT false,
    last_event_at         timestamptz,                         -- `created` of the newest Stripe event applied
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE billing_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_account
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON billing_account FROM erika_app;

-- Existing workspaces start a trial now. Before billing is switched on in production, the
-- operator sets the beta users' dates (docs/ops/billing.md).
INSERT INTO billing_account (workspace_id, trial_ends_at)
SELECT id, now() + interval '14 days' FROM workspace;

-- Stripe events arrive without a workspace: which workspace has this Stripe customer.
CREATE FUNCTION billing_workspace_for_customer(p_customer text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$ SELECT workspace_id FROM billing_account WHERE stripe_customer_id = p_customer $$;
REVOKE ALL ON FUNCTION billing_workspace_for_customer(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing_workspace_for_customer(text) TO erika_app;

-- ---------------------------------------------------------------------------
-- billing: Stripe webhook events (system table: an event belongs to no workspace until it is
-- processed). The ID makes delivery idempotent; the payload is processed by the worker.
-- ---------------------------------------------------------------------------
CREATE TABLE stripe_event (
    id            text        PRIMARY KEY,
    type          text        NOT NULL,
    created       timestamptz NOT NULL,
    payload       jsonb       NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz
);
CREATE INDEX stripe_event_received_idx ON stripe_event (received_at);
REVOKE DELETE, TRUNCATE ON stripe_event FROM erika_app;

-- ---------------------------------------------------------------------------
-- billing: ERIKA's own e-invoices for paid Stripe invoices. The rows belong to the issuing
-- (ERIKA) workspace, like the invoices themselves; customers read theirs through a function.
-- ---------------------------------------------------------------------------
CREATE TABLE billing_invoice (
    id                     uuid          PRIMARY KEY,
    workspace_id           uuid          NOT NULL REFERENCES workspace (id),   -- the issuer
    customer_workspace_id  uuid          NOT NULL REFERENCES workspace (id),
    stripe_invoice_id      text          NOT NULL UNIQUE,
    erika_invoice_id       uuid          NOT NULL,                             -- creator draft/invoice ID
    state                  text          NOT NULL DEFAULT 'ISSUING' CHECK (state IN ('ISSUING', 'ISSUED', 'REJECTED')),
    number                 text,
    issue_date             date,
    currency               text          NOT NULL,
    net                    numeric(15,2) NOT NULL,
    tax                    numeric(15,2) NOT NULL,
    gross                  numeric(15,2) NOT NULL,
    period_start           date,
    period_end             date,
    paid_at                timestamptz   NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX billing_invoice_workspace_idx ON billing_invoice (workspace_id, created_at DESC);
CREATE INDEX billing_invoice_customer_idx ON billing_invoice (customer_workspace_id, paid_at DESC);
CREATE INDEX billing_invoice_erika_idx ON billing_invoice (workspace_id, erika_invoice_id);

ALTER TABLE billing_invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_invoice
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE DELETE, TRUNCATE ON billing_invoice FROM erika_app;

-- A customer's own ERIKA invoices (only the current workspace's, never another's).
CREATE FUNCTION billing_invoices_of_current_workspace()
    RETURNS TABLE (id uuid, state text, number text, issue_date date, currency text, net numeric, tax numeric, gross numeric,
                   period_start date, period_end date, paid_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = erika, pg_temp
    AS $$
        SELECT b.id, b.state, b.number, b.issue_date, b.currency, b.net, b.tax, b.gross, b.period_start, b.period_end, b.paid_at
          FROM billing_invoice b
         WHERE b.customer_workspace_id = current_workspace_id()
         ORDER BY b.paid_at DESC
         LIMIT 100
    $$;
REVOKE ALL ON FUNCTION billing_invoices_of_current_workspace() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing_invoices_of_current_workspace() TO erika_app;

-- ---------------------------------------------------------------------------
-- creator: invoices issued by the system (ERIKA's own) are emailed right after issuing.
-- ---------------------------------------------------------------------------
ALTER TABLE invoice_draft ADD COLUMN send_to text[];
