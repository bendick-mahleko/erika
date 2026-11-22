# 0023. Intake pipeline, uploads and documents

- Status: Accepted
- Date: 2026-11-22

## Context
Stage 5 brings files into ERIKA by upload (IN-02) and email (IN-01, ADR 0024). The relevant requirements and design points:
- Every file is virus-scanned before parsing (NF-04).
- ZIPs are limited: depth 2, 50 files, zip-bomb limits (Software Design §5).
- Plain PDFs are archived and labelled (IN-04).
- Duplicates are flagged (IN-05).
- Heavy work runs in the worker, and every job is idempotent (Software Design §5).

Two points in the source documents need a decision:
- **Re-upload of identical bytes.** The design's `UNIQUE (workspace_id, sha256)` on `document` means a byte-identical file can't become a second document. IN-05's acceptance test, however, expects "re-uploading the same file shows 'possible duplicate'" (build plan §6, gap 1).
- **Module ownership.** The design lists "detect duplicates" under `intake` and "document records" under `documents`. Revalidation (Stage 6) also has to read and validate documents, so the parse-and-validate step can't belong to `intake` alone.

## Decision
**Modules.**
- `intake` receives files: the upload API, the mail endpoint, ZIP unpacking and the virus scan. It owns `intake`, `intake_item` and `intake_address`.
- `documents` owns `document`, `parsed_invoice` and `validation_result`. Through `Documents.registerIncoming` it turns an archived original into a document (status NEW) and queues `documents.parse-and-validate` in the same transaction.
- `intake` depends on `archive`, `documents` and `identity` (for notification recipients). `documents` depends on `archive` and `validation`.

**Upload flow (IN-02).**
1. `POST /uploads` announces name, size (at most 20 MB) and content type. The API returns a pre-signed `PUT` URL for the **uploads bucket** (`erika-uploads`). That bucket is a quarantine: no Object Lock, objects expire after a day, and the app may delete. Content type and length are signed, so the browser can't send more than it announced. The API never holds the bytes.
2. `POST /uploads/{id}/complete` checks that the object exists with the announced size, marks the upload RECEIVED and queues `intake.process-upload`.
3. The worker job:
   - unpacks ZIPs within the limits: depth 2, 50 files, 200 MB in total, and a compression ratio above 100 for an entry over 1 MB means a zip bomb;
   - scans every file with ClamAV (`zINSTREAM`). If ClamAV can't be reached, the job fails and retries: fail closed, never "clean by default";
   - accepts only XML and PDF, recognised by content rather than by file name;
   - archives each accepted file write-once (ADR 0022) and registers it as a document;
   - records one `intake_item` per file and deletes the quarantined upload after commit.

   Files are refused one by one, with a reason: VIRUS_FOUND, UNSUPPORTED_FILE_TYPE, EMPTY_FILE or FILE_TOO_LARGE. A ZIP that is too deep, has too many files, is a bomb, or is encrypted or damaged is refused as a whole.

**Byte-identical files.** The same bytes are one original and one document per workspace. A second upload of them returns the existing document, with outcome `ALREADY_IN_ARCHIVE`. The UI shows this as "Mögliches Duplikat – diese Datei ist bereits im Archiv", which meets IN-05's acceptance test. Nothing is stored twice.

**Business duplicates (IN-05).** After parsing, a document is flagged with `duplicate_of` when an earlier incoming document matches it on all of:
- supplier tax ID (BT-31, or BT-32 when there is no VAT ID), normalised;
- invoice number (BT-1);
- gross amount (BT-112);
- currency.

Using the gross amount and the currency is the reading proposed in build plan §6, gap 5. It is still to be confirmed.

**Parse and validate.**
- The `documents.parse-and-validate` job:
  - opens the original, which checks its hash;
  - runs `InvoiceValidator.check`, which returns the parsed model and the report from a single parse;
  - stores the model (`parsed_invoice.data`, without embedded attachment content) and the report (`validation_result`, with the pack ID);
  - sets format, profile, document type and the not-an-e-invoice reason;
  - runs the duplicate check;
  - publishes `DocumentValidated`.
- Plain PDFs become format PDF_ONLY, type OTHER, with reason PLAIN_PDF (IN-04).

**Retention.** Originals are archived at intake, before they are read, so retention starts from the receipt date. Once the issue date is known, the job extends the lock if the issue date gives a later end. Retention is never shortened (ADR 0022).

**Idempotency.**
- Each job runs in one transaction together with its completion mark (ADR 0010), so a crash leaves nothing behind and the retry starts over.
- Archive writes are idempotent by content.
- An intake that is no longer RECEIVED counts as processed.
- `validation_result.job_id` is unique.
- After the last attempt, a document is marked FAILED instead of staying PENDING forever. A savepoint keeps half-written results out.

**Notifications.**
- In-app: `GET /inbox/summary` provides the count for the navigation badge.
- Email: members who opted in (`membership.notify_new_documents`) get one email per processed intake. The uploader is not notified about their own upload. The email contains no invoice content.

**Alerts.** The API publishes two job-queue gauges (the worker has no web server to scrape), and alert rules are in `infra/monitoring/alerts.yml`:
- `erika_jobs_oldest_due_seconds{type}`: alert when the intake queue is older than 5 minutes;
- `erika_jobs_failed_recent{type}`: alert on failed jobs.

## Consequences
- The browser uploads directly to object storage, so the uploads bucket needs a CORS rule for the web origin. Locally, `ERIKA_S3_PUBLIC_ENDPOINT` points the signature at the host the browser can reach.
- The worker needs ClamAV, which uses about 1.2 GB of memory for its signatures. The integration tests start the real ClamAV image. Its first start takes about a minute.
- `JobQueue` looks up job handlers lazily: handlers may queue jobs themselves (intake → validation), so resolving them eagerly would be a dependency cycle.
- Uploads that are announced but never completed stay in AWAITING_FILE; their objects expire through the bucket lifecycle rule. A clean-up of such rows can come later.
- Stage 6 builds on this: status changes, revalidation (a new `validation_result` per run, history kept), search and the viewer.
