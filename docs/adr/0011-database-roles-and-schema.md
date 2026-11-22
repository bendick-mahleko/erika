# 0011. Database roles and dedicated schema

- Status: Accepted
- Date: 2026-11-22

## Decision
- Schema `erika`, owned by `erika_owner`; `public` is closed.
- `erika_owner` runs Flyway migrations (`spring.flyway.user`), only in the `api` role.
- `erika_app` is used by the running application: `NOSUPERUSER NOBYPASSRLS`, schema `USAGE` only, DML on tables through `ALTER DEFAULT PRIVILEGES` (V1). Append-only tables (`audit_log`, `stored_object`) will `REVOKE UPDATE, DELETE` explicitly.
- Roles and schema are created outside Flyway: `infra/local/postgres/01-roles.sql` (local and tests, dev passwords), `infra/staging/postgres/01-roles.sh` (passwords from env), IaC in production (Stage 7).

## Consequences
`DatabaseRolesIT` proves the app role can't change the schema and doesn't bypass RLS. Tests use the same bootstrap script as local development.
