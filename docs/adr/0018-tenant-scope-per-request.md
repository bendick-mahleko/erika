# 0018. Tenant scope per request and the isolation checks

- Status: Accepted
- Date: 2026-11-22

## Context
Software Design §7: every request opens a transaction and runs `SET LOCAL app.workspace_id`; row-level security (RLS) filters every tenant table; the workspace comes from the session, never from the URL; an automated suite proves that tenant A can't reach tenant B's data (NF-03).

## Decision
- **One transaction per signed-in request.** `TenantTransactionFilter` runs after Spring Security, opens a transaction, sets `app.workspace_id` from the session principal (`set_config(..., true)` = `SET LOCAL`) and runs the rest of the request inside it. A response status of 400 or more rolls back, also when an exception handler produced the response. Requests without a session (health, sign-in) run without this scope.
- **Policies use `erika.current_workspace_id()`**, which returns NULL when the setting is missing or empty. A forgotten scope then shows no rows instead of failing open.
- **Sign-in needs a user scope.** Before a workspace is chosen, a user may see their own memberships: `membership` and `workspace` policies also allow rows of `app.user_id`. That setting is used only inside the sign-in and verification flows, never in normal requests, so a user with several workspaces sees only the session's workspace.
- **System jobs** that must see all workspaces (e.g. the audit chain check) get the IDs from the `SECURITY DEFINER` function `all_workspace_ids()`, which returns IDs only. They then scope themselves to each workspace in turn.
- **Migration lint as a test.** `TenancyDatabaseIT` reads the migrated catalogue. Every table not on its documented list of system tables must have `workspace_id NOT NULL`, an index starting with `workspace_id`, RLS enabled and a policy using `current_workspace_id()`. As the app role without a scope, every tenant table must return 0 rows.
- **Isolation harness from the contract.** `TenantIsolationIT` reads `api/openapi.yaml` and calls every operation:
  - Signed in as A, operations with path parameters are called with B's IDs and must answer 404.
  - Operations without path parameters must not return any of B's identifiers or names.
  - B's own view is compared before and after, and must be unchanged.
  - Without a session, every operation that isn't public must answer 401, and only health and `/auth/*` may be public.
  - A path parameter without a tenant-B fixture, or a request body without an `example`, fails the build. That way every new endpoint is covered.

## Consequences
- A signed-in request holds a database connection for its whole duration. That is fine for short API calls; long-running work goes to the worker.
- Later stages add their path-parameter fixtures (`documentId`, `invoiceId`, …) to the harness and list any new system table, with its reason, in `TenancyDatabaseIT`.
