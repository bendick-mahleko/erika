# Distributing the app: Google Play internal testing and TestFlight

The workflow `.github/workflows/mobile-release.yml` (manual, "Run workflow") builds, signs and uploads the app. The build number is the workflow run number; the version name comes from `mobile/pubspec.yaml` (keep `lib/app/version.dart` equal, checked by `version_test.dart`).

## One-time set-up

### Google Play

1. Google Play Console developer account (organisation), app **de.erika.mara**, "Internal testing" track with a tester list (the field-test technicians' Google accounts).
2. Upload key: `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`. Kept in the password manager, never in the repository (`*.jks` is ignored). Use Play App Signing (Google holds the app signing key).
3. First upload by hand (Play requires it before the API works): run the build locally with the upload key (see below) and upload the `.aab` in the console.
4. Service account with "Release to testing tracks" permission; its JSON key goes into the secret below.
5. Data safety form: the app collects no data for advertising; crash reports (no personal data) and content-free usage counters are sent to the operator's own servers.

### Apple

1. Apple Developer Program (organisation), App ID **de.erika.mara**, app record in App Store Connect, TestFlight internal and external testers.
2. Distribution certificate (`.p12`) and an App Store provisioning profile named **MARA App Store**; set the team ID in `mobile/ios/ExportOptions.plist`.
3. App Store Connect API key (role "App Manager") for the upload.
4. Export compliance: the app uses standard encryption only (HTTPS, SQLCipher) → `ITSAppUsesNonExemptEncryption` = NO after confirming with the owner.

### GitHub environment "mobile-release"

Required reviewers: the owner. Secrets and variables:

| Name | Kind | What |
|---|---|---|
| `MARA_API_BASE_URL` | variable | e.g. `https://staging.<domain>` for the field test |
| `MARA_SENTRY_DSN` | secret | GlitchTip project "mara-app" |
| `MARA_UPLOAD_KEYSTORE_BASE64` | secret | `base64 -w0 upload.jks` |
| `MARA_UPLOAD_KEYSTORE_PASSWORD`, `MARA_UPLOAD_KEY_ALIAS`, `MARA_UPLOAD_KEY_PASSWORD` | secret | the upload key |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | secret | service account key |
| `APPLE_DISTRIBUTION_P12_BASE64`, `APPLE_DISTRIBUTION_P12_PASSWORD` | secret | distribution certificate |
| `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_PRIVATE_KEY` | secret | API key |

## Releasing a build

1. Bump `version:` in `mobile/pubspec.yaml` and `appVersion` in `mobile/lib/app/version.dart`; merge.
2. Actions → **Mobile release** → environment `field-test`, Android and/or iOS → Run; approve the environment.
3. The job runs the tests, builds, checks the size (< 60 MB) and uploads. Android appears in internal testing within minutes; iOS after Apple's processing (up to an hour), then enable it for the testers in TestFlight.
4. If the server needs the new version, raise `ERIKA_MOBILE_MIN_APP_VERSION` on staging only after the build is available to everyone (ADR 0038).

## Building locally with the upload key

```sh
cd mobile
MARA_UPLOAD_KEYSTORE=/path/upload.jks MARA_UPLOAD_KEYSTORE_PASSWORD=… MARA_UPLOAD_KEY_ALIAS=upload MARA_UPLOAD_KEY_PASSWORD=… \
  flutter build appbundle --release \
  --dart-define=MARA_API_BASE_URL=https://staging.<domain> \
  --dart-define=MARA_SENTRY_DSN=<dsn> --dart-define=MARA_ENVIRONMENT=field-test
tool/check_size.sh build/app/outputs/bundle/release/app-release.aab
```

Without `MARA_UPLOAD_KEYSTORE`, release builds are signed with the debug key: fine for testing on a device, refused by Google Play.
