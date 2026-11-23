# 0026. Document status, history and search

- Status: Accepted
- Date: 2026-11-23

## Context
AR-03 asks for statuses NEW, CHECKED, APPROVED, PAID, DISPUTED and BOOKED, with history (user, time, note) and tags. AR-02 requires every change to be audited. AR-04 asks for search across 10,000 invoices in under a second; the build plan names German full-text search and `pg_trgm` for supplier names.

## Decision
**Status.**
- Any status can follow any other: small businesses correct mistakes, and the plan sets no workflow.
- Setting the current status again changes nothing.
- Each change writes a `status_event` row (from, to, note, user, time). The app role can't update or delete these rows.
- Each change also publishes `DocumentStatusChanged`, which goes into the audit chain.
- `PATCH /documents/{id}` changes status, tags or note. `POST /documents/status` sets the status of up to 200 documents, with one history and audit entry each.
- Tags and note are metadata on `document`; the original never changes. Their audit entry names the fields, not the values, which may contain invoice content.
- Tax advisors can read but not change anything (security configuration, EX-02).

**History names.** `status_event` stores the user ID. The display name comes from identity's published `WorkspaceContacts.displayNames`, which only names members of the current workspace. `documents` now depends on `identity`.

**Search** (`GET /documents?q=…&status=…&from=…&to=…&tag=…`):
- `parsed_invoice.search_text` (invoice number, seller and buyer names, references, item names, notes) has a generated `tsvector` with the `german` configuration and a GIN index. `q` matches it with `websearch_to_tsquery`.
- Supplier names also match by substring (`ILIKE`) and by trigram similarity (`%`), which catches typos, using a `gin_trgm_ops` index. Invoice numbers match by prefix, file names by substring.
- The date filters use the invoice date (BT-2), or the receipt date for documents without one.
- Keyset pagination on (received time, ID) stays stable while new documents arrive.

**`pg_trgm`** must be created by a superuser: `CREATE EXTENSION pg_trgm SCHEMA erika`. The migration role can't create extensions. The bootstrap scripts (`infra/local/postgres/01-roles.sql`, `infra/staging/postgres/01-roles.sh`) do it, and migration V5 stops with a clear message if it's missing. Existing local databases need the command once (`docs/dev-setup.md`).

## Consequences
- `DocumentsIT` seeds 10,000 invoices and checks that full-text, trigram, number and filter searches each answer in under a second.
- Production database provisioning (Stage 7) must include the extension.
- A status workflow, such as "PAID only after APPROVED", can be added later as a rule in `Documents.changeStatus` without changing the schema.
