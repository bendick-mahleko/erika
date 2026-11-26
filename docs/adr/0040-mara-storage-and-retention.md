# 0040. MARA storage: device database, photos, signed reports and retention

- Status: Accepted (device database in MARA Stage 1, photos in Stage 6, signed reports in Stage 7); retention periods and the storage allowance await the lawyer, the tax advisor and the owner
- Date: 2026-11-25

## Context
MARA stores customer data, photos of customers' homes, signatures and employees' working times on phones and on the server. Signed reports are evidence (M-BR-01, M-BR-02). Retention follows the requirements (M-BR-08) and still needs a lawyer's and a tax advisor's confirmation. Photos are the biggest cost driver.

## Decision
- **On the device (implemented in Stage 1).**
  - drift with SQLite, encrypted with SQLCipher. `package:sqlite3` 3.x bundles the SQLCipher build through its build hooks (`pubspec.yaml`).
  - The 256-bit key comes from the platform's secure random generator and is kept in the Android Keystore / iOS Keychain (`flutter_secure_storage`). From Stage 3 it is released only after PIN or biometric unlock (ADR 0037).
  - The app refuses a database without SQLCipher, and a wrong key fails on opening. Tests run against the real SQLCipher build.
  - Photos and PDFs are kept in app-private storage, never in the gallery, and are excluded from cloud backups (Stage 6).
- **Uploads (Stage 6).**
  - Photos arrive through tus under the `uploads/` prefix; the hash sent by the app is checked.
  - Every file is virus-scanned in the worker, fail closed, as for intake (ADR 0023).
  - Thumbnails are derived objects.
- **Signed reports (Stage 7).**
  - The server renders the master PDF and stores it write-once through `archive.Archive`, together with the photos of the signed report.
  - Retention, set per object when it is stored:

    | Object | Kept |
    |---|---|
    | Report PDF | 8 years from the end of the year of signing |
    | Photos | 5 years after the job by default (warranty for building work, § 634a BGB); a firm can choose up to 10 years |

  - Object Lock can extend a retention period but never shorten it. So a firm's change applies to new objects, and extensions of existing ones are done by a job.
- **Time entries.** Kept in the database for 2 years by default (proof of working time); a firm can set longer.
- **Invoices.** Kept by ERIKA as before (8 years); the report PDF embedded in an invoice is part of that invoice's original.
- **Storage allowance.** The proposal is 20 GB of photos per firm in the add-on price, with a warning at 80 %. The owner decides before Stage 6 (build plan §7).
- **Licences.** The SQLCipher community edition and, on Android, OpenSSL have their own licences. They are listed on the app's licence page before store release (Stage 10).

## Implementation (MARA Stage 6: photos)
- **The record first, then the file.** A `photo` record (module `media`, table `photo`, V16) syncs at once with state `PENDING_FILE`, its size (at most 15 MB) and SHA-256. It belongs to a report and is visible where the report is (`reports.ReportDirectory`). It is deletable (softly) and may answer a checklist item (`checklistItemKey`): a mandatory PHOTO item counts as answered once a photo for it exists that wasn't rejected (`reports.ReportMedia`, implemented by `media`).
- **tus on a fixed URL.** The tus core protocol runs at `/api/v1/mobile/uploads/photos/{id}` (`HEAD` for the offset, `PATCH` with chunks of about 1 MB, at most 2 MB), with no creation request: the record already holds size and hash.
  - S3 can't append, so every chunk is a part named by its offset under `photos/<workspace>/<photo>/parts/` in the uploads bucket. The row's `upload_offset`, locked per request, says how far the upload is.
  - Each chunk runs in a transaction of its own: the request transaction rolls back on every error answer, but a checksum mismatch must still commit the reset.
  - The last chunk assembles the parts and checks the SHA-256. On a mismatch, or a part lost to the uploads bucket's one-day expiry, the answer is `460 CHECKSUM_MISMATCH`, the offset goes back to 0 and the app uploads again. On a match the state becomes `UPLOADED` and the worker job `media.process-photo` is queued.
  - The endpoints are a hand-written controller: the generator maps the raw `application/offset+octet-stream` body to a multipart file. `MobileContractIT` checks them against the contract.
- **Worker.** It checks the hash again and scans for viruses, failing closed (an unreachable ClamAV fails the job, which is retried). Then:
  - not a decodable JPEG: `REJECTED`, reason `NOT_AN_IMAGE`; a virus: `REJECTED`, reason `VIRUS`;
  - otherwise the photo and a 400 px thumbnail go to the new **media bucket** (`<prefix>-media`, versioned, no expiry; Terraform, local SeaweedFS, `ERIKA_S3_MEDIA_BUCKET`) and the state is `STORED`.

  The phones learn the state through sync.
- **Thumbnails in the media bucket**, not in the derived bucket: until Stage 7 moves signed reports' photos to the archive, the photo itself isn't an original that the thumbnail could be rebuilt from.
- **Office.** The work order page shows the photos with placeholders while they are pending (`GET /photos/{id}/thumbnail`). `GET /photos/{id}/file` serves the photo as a download (attachment, nosniff, sandbox CSP). Only STORED photos are served.
- **On the phone.**
  - The platform camera (`image_picker`) compresses on capture (long side 2,048 px, quality 80) into the app's cache, never the gallery.
  - Photos are kept in `<Application Support>/photos/`. iOS excludes that folder from iCloud backups through a method channel in `AppDelegate.swift`; Android switches backups and device transfer off (`allowBackup=false`, `data_extraction_rules.xml`).
  - Before the first photo, the app asks the user not to photograph people or documents (M-NF-08).
  - Uploads start after each successful data sync and never hold it up. A photo is uploaded only once the server has its record. The optional "Wi-Fi first" setting waits for Wi-Fi.
  - **The photo files are not encrypted** on the device (the database is): they are app-private and wiped with the rest on revocation. Encrypting them is a Stage 11 decision.
- **Open:** the storage allowance (20 GB per firm is the proposal) is still the owner's decision; nothing is counted or limited yet. The image files are not yet part of the account export (`fotos.json` has the records; the files follow with Stage 8's export work).

## Implementation (MARA Stage 7: signatures, immutability, report PDF)
- **What is signed.** The canonical report (`mara-rules/vectors/report/canonical.json`, Kotlin `ReportCanonical`, Dart `report_canonical.dart`): work order title, customer name, work description, times with the technician's name, materials, Aufmaß, checklist answers and photos (with their SHA-256). It is JSON with sorted keys and lists, trimmed text, decimals without trailing zeros and times in UTC to the second. Prices are never part of it.
  - The phone builds it from its records (`report_snapshot.dart`) and shows exactly that in the preview. The server builds it from its data (`ReportSnapshots`).
- **The signature record** (`report_signature`, sync entity, create-only, ID derived from the report) holds:
  - the kind (`SIGNED`, or `NOT_SIGNED` with a reason, M-BR-03);
  - the SHA-256 the phone computed;
  - the printed name, the phone's time, an optional comment;
  - the strokes and the PNG;
  - a 10-character verification code;
  - the SHA-256 of a download key;
  - an optional customer email.
- **Hash check.** The server recomputes the hash and refuses a different one (`HASH_MISMATCH`). The metric `erika_mara_signature_hash_mismatch_total` drives the alert `ErikaMaraSignatureHashMismatch` (runbook `docs/ops/mara.md`). Completion checks apply as for COMPLETED.
- **Locked for good** (M-MO-10).
  - The report becomes `SIGNED`/`NOT_SIGNED`. Triggers (V17) refuse every change to the report row, to its times, materials, Aufmaß and answers, and to what the customer signed about its photos. The photos' upload state may still change.
  - The signature itself never changes, apart from the worker setting the archived PDF once. This holds for the app role through plain SQL too (`SignaturesIT`).
  - A SIGNED report sets the work order to SIGNED; the app's lock is per report version, and only INVOICED locks a work order's reports.
- **Corrections** (M-BR-01). Version *n+1* (ID derived as `report/<n+1>`) may be created only after version *n* is signed or sent unsigned.
  - The server copies times, materials, Aufmaß, answers and photos into it. Photo copies share the original's files (`photo.file_photo_id`) and follow its scan result.
  - It must be signed (or sent unsigned) again and gets its own PDF and email. The public check marks older versions as superseded.
- **Worker `reports.finalize`.**
  - Waits up to a day (`erika.reports.photo-wait`, 48 rounds) for photos still on their way.
  - Renders the master PDF with PDFBox from the server's data: canonical content, signature image, photo thumbnails, the hash and the verification URL on every page.
  - Stores it through `archive.Archive`, under the 8-year rule from the signing date.
  - Stores the photos of the signed version with **5 years from the signing date**, a new retention override of `Archive.store`.
  - Emails the PDF if an address was given. The message is archived first (like invoices, CR-07) and logged in `report_delivery`.
- **On the phone** (M-MO-09 to M-MO-11).
  - The preview shows the canonical report. The signature pad records strokes, which are rendered to a PNG.
  - The customer PDF is rendered on the device with the `pdf` package (standard fonts, Latin-1). It carries the hash, the code and a QR code linking to `/verify/<code>?k=<key>`, and can be shared with the platform share sheet.
  - Signing works offline: the signature waits in the outbox and the email goes out with the next sync.
- **Public check** (`GET /public/report-verifications/{code}`, page `/verify/<code>`).
  - Without a session, it shows firm, status, version, time and hash, never content.
  - With the key from the QR code, it serves the archived PDF.
  - The lookup is a `SECURITY DEFINER` function. Codes are unique across all workspaces.
- **Office.** The work order page shows the signature, the versions and a download of the archived PDF (`GET /reports/{id}/pdf`).
- **Deviation:** the customer email carries the server's master PDF, not the phone's: both have the same canonical content and hash, and the server's copy is the archived one.
- **Open:** the lawyer's review of the signature approach (M-BR-02, § 640 BGB) and the retention periods (8/5/2 years); the "signing to PDF in under 3 s" check needs the low-end reference device (M-NF-06).

## Consequences
- A lost phone exposes nothing without the unlock, and remote wipe removes the key (ADR 0037).
- Archived photos can't be deleted early, not even at account closure. The terms and the data-processing agreement must say so, as they do for invoices.
- Storage cost grows with photos; the allowance and older photos moving to cheaper storage are the levers.
