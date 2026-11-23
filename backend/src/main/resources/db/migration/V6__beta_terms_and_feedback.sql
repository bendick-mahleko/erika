-- Stage 7: closed beta (ADR 0030).

-- Which version of the terms (incl. the data-processing agreement) a user accepted, and when.
-- Users from before Stage 7 have none; they accept on their next sign-up step (later stage).
ALTER TABLE app_user ADD COLUMN terms_version text;
ALTER TABLE app_user ADD COLUMN terms_accepted_at timestamptz;

-- Feedback from users to the ERIKA team. Written by the app, read by the team as the
-- schema owner; the app never changes or deletes it.
CREATE TABLE feedback (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspace (id),
    user_id       uuid        NOT NULL,
    category      text        NOT NULL CHECK (category IN ('PROBLEM', 'IDEA', 'PRAISE', 'OTHER')),
    message       text        NOT NULL,
    page          text,
    app_version   text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX feedback_workspace_idx ON feedback (workspace_id, created_at);

ALTER TABLE feedback ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON feedback
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());
REVOKE UPDATE, DELETE, TRUNCATE ON feedback FROM erika_app;
