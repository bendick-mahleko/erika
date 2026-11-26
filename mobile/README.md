# MARA mobile app (Flutter)

The technician app of MARA, ERIKA's job-report add-on. Plan: [docs/mara/BUILD_PLAN.md](../docs/mara/BUILD_PLAN.md). Decisions: ADR 0036–0040 in [docs/adr/](../docs/adr/README.md).

**State (MARA Stage 5):** a whole job report on site, offline: times by timer or entered afterwards (several technicians), materials from the article list or free text (recent items in three taps), Aufmaß with deductions and formulas, checklists with mandatory items, a work description (typed or dictated with the keyboard). Completing the report needs the mandatory items and no running timer. There is a high-contrast mode in the menu.

Stage 6: photos with caption and category (before, after, defect), also for checklist photo items. They are compressed on capture, kept only in the app's folder (not in the gallery, not in cloud backups) and uploaded with tus after each sync, resuming after interruptions; "Wi-Fi first" is in the menu.

From Stage 4: the work orders sync with ERIKA in both directions (ADR 0038): today's and upcoming work orders, status and work notes, route and call, new work orders and customers on site, a conflict inbox. All of it works offline.

From Stage 3: device sign-in with password and TOTP (2FA can be set up in the app), PIN or biometric unlock, lock after 5 minutes in the background, sign-out, and deletion of all local data when the device is revoked (ADR 0037). After unlocking, a status screen shows who is signed in, the server check and the encrypted database. Photos, the signature and the report PDF follow in Stages 6 and 7.

## Layout

| Folder | Contents |
|---|---|
| `lib/app/` | version (keep equal to `pubspec.yaml`) and build-time config |
| `lib/data/api/` | HTTP clients for `api/mobile.yaml` |
| `lib/data/local/` | drift database (SQLCipher) and the database key generator |
| `lib/data/sync/` | sync protocol: `SyncStore` (records, outbox, inbox), `SyncEngine` (push, then pull), `SyncApi`, the `SyncCoordinator` that decides when to sync |
| `lib/data/session/` | session state (`SessionController`), key vault (PIN-wrapped database key), keystore, biometrics and local files behind interfaces |
| `lib/domain/rules/` | rules shared with the back end; tested against `../mara-rules/vectors/` |
| `lib/data/media/` | photos: `PhotoFiles` (app folder), `TusTransport` (tus client), `PhotoUploader` (the queue), `UploadCoordinator` (after each sync, "Wi-Fi first") |
| `lib/features/reports/` | the job report: times, materials, Aufmaß editor, checklist, description; `ReportWriter` creates the report (derived ID) on the first write |
| `lib/features/` | screens and their Riverpod providers (screens never touch the database directly) |
| `lib/l10n/` | `app_de.arb` (template, German first) and `app_en.arb`; same keys (checked by a test) |

Generated, never edited, not committed: `*.g.dart` (drift, `build_runner`) and `lib/l10n/gen/` (`flutter pub get`).

## Commands

With Flutter 3.44 installed, from `mobile/`:

```bash
flutter pub get                                       # also generates lib/l10n/gen
dart run build_runner build --delete-conflicting-outputs   # drift code (*.g.dart)
dart format --line-length 100 lib test
flutter analyze
flutter test                                          # includes the real SQLCipher database tests
flutter run --dart-define=MARA_API_BASE_URL=http://10.0.2.2:8080   # Android emulator → API on this machine
```

iOS simulator: `--dart-define=MARA_API_BASE_URL=http://localhost:8080`. A real phone needs your machine's LAN address and the API listening on it.

### Without a local Flutter installation

CI uses the same pinned image, so this gives the same results:

```bash
# from the repository root (Git Bash: prefix with MSYS_NO_PATHCONV=1)
docker run --rm -v "$PWD:/work" -v mara-pub-cache:/pub-cache -e PUB_CACHE=/pub-cache -w /work/mobile \
  ghcr.io/cirruslabs/flutter:3.44.0 sh -c 'flutter pub get && dart run build_runner build --delete-conflicting-outputs && flutter analyze && flutter test'
```

Add `-v mara-gradle:/root/.gradle` and `flutter build apk --debug` for an Android build. Running on an emulator or a phone needs a local Flutter and Android Studio or Xcode.

## Rules

- **Decimal arithmetic** for every quantity, dimension and amount (`package:decimal`), never `double`. Rules that also run on the server get vectors in `mara-rules/` first.
- **The local database is always encrypted.** `AppDatabase.openEncrypted` refuses to run without SQLCipher. Never open the file another way.
- **The database key is never stored unwrapped.** Only `KeyVault` keeps it (PIN-wrapped; biometric copy only if chosen). API calls with a token go through `SessionController.authorized`, which renews tokens one at a time and wipes on `DEVICE_REVOKED`.
- **No customer or report content in logs or crash reports.**
- **Texts in both ARB files**, German first.
- **Tap targets of at least 48 dp** (M-NF-05).
