# 0003. Shared schema with row-level security

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, D3 / ADR 003)

## Decision
All tenants share one schema. Every tenant table has `workspace_id NOT NULL`, an index and an RLS policy on `current_setting('app.workspace_id')`, set per transaction (`SET LOCAL` / `set_config(..., true)`). Request scoping arrives in Stage 3; the job worker already scopes workspace jobs.

## Consequences
Schema-per-tenant (hard migrations) and database-per-tenant (cost) are avoided. Isolation depends on the app role never bypassing RLS (see 0011).
