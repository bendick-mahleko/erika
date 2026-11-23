# 0032. Export, roles and account lifecycle

- Status: Accepted (DATEV import with a tax advisor pending, spike S9-a)
- Date: 2026-11-23

## Context
Stage 9 covers EX-01, EX-02 (complete), EX-04 and NF-09:
- the tax advisor gets a period export without retyping anything;
- the advisor can be invited with read-only rights;
- an owner can export everything and close the account.

Rules from earlier stages apply:
- tenant tables use RLS;
- feature modules don't read each other's tables;
- originals are opened only through `archive.Archive`;
- tax advisors never change data (Stage 3);
- the API doesn't build files (ADR 0025).

## Decision

### Invitations and roles (EX-02)
- **Invitations.** Owners invite by email as MEMBER or ADVISOR (`/invitations`, `invitation` table).
  - The link carries a random token; only its SHA-256 is stored. It is valid for 7 days.
  - A new invitation to the same address revokes the open one.
  - Owners aren't invited directly. A member can be promoted later.
- **Accepting.** The invitee opens `/invitations/accept?token=…`. The page reads the invitation through `POST /auth/invitation`, a SECURITY DEFINER lookup by token hash, because the invitee has no session in that workspace yet. Then:
  - with an existing account, signed in with the invited address: `POST /auth/invitation/accept`. The session moves to the joined workspace;
  - without an account: sign-up with `workspaceInvitation`. No workspace of their own is created. The invitation also counts as the closed-beta invitation (ADR 0030), because it was sent to that address.
- **Several workspaces.** `GET /auth/session` lists the user's workspaces (`workspaces_of_user`, SECURITY DEFINER). This keeps the request's RLS scope at one workspace. `POST /auth/workspace` switches the session; the session ID rotates and the role is that of the membership.
- **Role changes and removal.** `PATCH`/`DELETE /members/{id}` (owner only). The last owner can't be demoted or removed: `409 LAST_OWNER`, with the owner rows locked `FOR UPDATE`.
- **Membership checked on every request.** `identity.web.WorkspaceAccessInterceptor` compares the session's role with the current membership inside the tenant transaction.
  - A removed or re-assigned member is signed out: `401 SESSION_REVOKED`, or ANONYMOUS on `/auth/`.
  - So the session never outlives the membership.
- **What advisors may write.** The security configuration still lets ADVISOR send no state-changing request except:
  - `POST /feedback` (ADR 0030);
  - `POST /documents/{id}/comments`;
  - `POST /exports` (period exports; the full export is owner-only in the service).
- **Advisor test generated from the contract.** `AdvisorReadOnlyIT` sends every other write operation in `api/openapi.yaml` as ADVISOR, with real IDs of the workspace. Each must answer 403, and the workspace's data must be unchanged afterwards. Opening an operation to advisors needs an entry with a reason in that test.

### Comments
- `document_comment` (documents module) is append-only: the app role has no UPDATE or DELETE.
- Comments are audited without their text (`document.comment.added`); the text may contain invoice content.

### Period export (EX-01)
- `POST /exports {kind: PERIOD, from, to, scope}` creates a `data_export` row and queues `export.build`. The worker builds the ZIP in a temporary file:
  - `Belege/Eingang|Ausgang/<date>_<number>_<file>`: the originals via `Archive.open`, hash-checked;
  - `index.csv`: file, SHA-256 and invoice data; UTF-8 with BOM, semicolons, decimal comma, protected against CSV injection;
  - `DATEV/EXTF_Buchungsstapel_<from>_<to>.csv`: one file per fiscal year;
  - `LIESMICH.txt`.
- **Which documents.** The period uses the invoice date (BT-2), or the receipt date for documents without data. The documents come from the published `documents.DocumentExports`.
- **DATEV format.** DATEV format EXTF version 700, category 21 "Buchungsstapel", format version 13, 125 columns, Windows-1252, CRLF.
  - One booking per invoice and VAT rate (BG-23), against a personal account: creditor 70000 for received invoices, debtor 10000 for issued ones.
  - The other side is a general ledger account without automatic VAT: SKR03 4900/8200, SKR04 6300/4200. All accounts can be changed in `datev_settings`.
  - Tax keys 9/8 (input VAT 19/7 %) and 3/2 (output VAT 19/7 %).
  - Reverse charge, intra-EU supply and exempt cases get no key and are left to the advisor; the buyer's VAT ID goes into "EU-Land u. UStID".
  - Credit notes (381) and negative amounts swap debit and credit.
  - Belegfeld 1 is the invoice number, reduced to the characters DATEV allows.
  - A stable "Buchungs GUID" per line means a re-import is recognised.
  - Festschreibung 0, so the advisor can still change every line.
- **No consultant or client number.** The ZIP contains the originals and the index, `LIESMICH.txt` explains why there is no DATEV file, and the export page shows the same notice.
- **Storage and download.** The ZIP goes to the `exports` bucket (no lock), key `exports/<workspace>/<export>.zip`.
  - The requester gets an email with a link to `/exports`. The link carries no secret; the page needs a session.
  - `GET /exports/{id}/download` (signed-in users of the workspace) answers 303 with a 60-second pre-signed URL. The tenant filter buffers responses, so a large ZIP must not stream through the API.
  - Every download is audited (`export.downloaded`).
- **Expiry.** The ZIP can be downloaded for 7 days. A daily worker task then deletes it and marks the export EXPIRED. A bucket lifecycle rule (8 days) catches anything left over.
  - *Deviation from the build plan* ("deleted after 7 days; email with a 24 h link"): a link that only works after sign-in contains no secret, so its own 24-hour expiry would protect nothing. We keep one validity period, 7 days, for the email link and the download.
- **Retries and failure.** The job is idempotent: an export that is no longer QUEUED is skipped, and a retry overwrites the object. After 3 attempts the export becomes FAILED and the requester is told; building runs in a savepoint, so the failure can still be recorded.

### Full account export (EX-04, NF-09)
- `POST /exports {kind: FULL}` (owners only). The ZIP contains:
  - `archiv/<sha256>.<ext>`: every archived original, including received and sent emails;
  - `archiv.json`: media type, retention date and lock of each original;
  - `daten/*.json`: one file per module;
  - `MANIFEST.sha256`: the SHA-256 of every file, in `sha256sum -c` format.
- **Each module supplies its own data.** Modules contribute through `platform.export.AccountDataSection` beans, so `export` never reads another module's tables:
  - identity: workspace, members, invitations; no secrets;
  - documents: metadata, invoice data, validation results, history, comments;
  - creator: settings, customers, articles, drafts, invoices, deliveries;
  - audit: the complete hash chain, hashes as hex.
- **Completeness is tested.** `ExportIT` counts and hashes the originals in the ZIP against `stored_object` and checks the manifest and the chain sequence.

### Account closure (EX-04)
- `POST /workspace/closure {confirmName}` (owner only; the workspace name must be typed exactly):
  - sets `closed_at` and `closed_by`;
  - sets `delete_after` to the latest `retention_until` of the workspace's originals, or today if it has none (`Archive.lastRetentionDate`);
  - publishes the audited `identity.WorkspaceClosed`.
- **Can't be undone in the app.** A trigger refuses every later change to a closed workspace row, reopening included.
- **Read-only.** The interceptor answers 403 `WORKSPACE_CLOSED` to every state-changing request except exports, feedback and the sign-in endpoints. Members can still sign in, read, download and export.
- **No more mail.** The intake address of a closed workspace is treated as unknown (`intake_workspace_for_token`), so the mail server refuses mail to it.
- **Billing.** Stage 10 cancels the subscription on `WorkspaceClosed`; the event is published API.
- **Deletion after `delete_after`.** This is an operator procedure for now (docs/ops/gdpr.md). An automatic deletion job comes with Stage 11, once the Object Lock end-of-contract question is answered (docs/legal/README.md).

## Consequences
- An export holds the workspace transaction for the time it takes to build the ZIP. That is fine for the expected sizes (hundreds of documents per period). Larger workspaces may need a streaming read later.
- `jsonRows` exports some tables with their column names (snake_case). The structure follows the schema, which is documented in the migrations.
- Every signed-in request costs one more indexed query (membership and closure check).
- The DATEV file is accepted only once a tax advisor has imported it (EX-01 exit criterion, spike S9-a). The account defaults are a starting point and may change with that feedback.
