# MARA mobile app (Flutter)

The technician app of MARA, ERIKA's job-report add-on. Plan: [docs/mara/BUILD_PLAN.md](../docs/mara/BUILD_PLAN.md). Decisions: ADR 0036–0040 in [docs/adr/](../docs/adr/README.md).

**State (MARA Stage 1):** one screen that checks the server (`GET /api/v1/mobile/status`) and opens the encrypted local database. Sign-in, sync and the report screens follow in Stages 3–7.

## Layout

| Folder | Contents |
|---|---|
| `lib/app/` | version (keep equal to `pubspec.yaml`) and build-time config |
| `lib/data/api/` | HTTP clients for `api/mobile.yaml` |
| `lib/data/local/` | drift database (SQLCipher) and the key store |
| `lib/domain/rules/` | rules shared with the back end; tested against `../mara-rules/vectors/` |
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
- **No customer or report content in logs or crash reports.**
- **Texts in both ARB files**, German first.
- **Tap targets of at least 48 dp** (M-NF-05).
