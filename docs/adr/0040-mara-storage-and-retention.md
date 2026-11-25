# 0040. MARA storage: device database, photos, signed reports and retention

- Status: Proposed (device database in MARA Stage 1; server storage in Stages 6 and 7)
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

## Consequences
- A lost phone exposes nothing without the unlock, and remote wipe removes the key (ADR 0037).
- Archived photos can't be deleted early, not even at account closure. The terms and the data-processing agreement must say so, as they do for invoices.
- Storage cost grows with photos; the allowance and older photos moving to cheaper storage are the levers.
