# MARA field test (Stage 9)

Real jobs in basements and on building sites, before anyone pays: do offline work and sync hold up, and does the app fit the way trades work? This folder is the playbook. The software side (telemetry, crash reports, store builds, size check, robustness tests) is built; the field test itself is run by people.

| Document | What it's for |
|---|---|
| [setup.md](setup.md) | Environment, firms, accounts, data protection, before day 1 |
| [distribution.md](distribution.md) | Store accounts, secrets, building and uploading the app |
| [devices.md](devices.md) | The 5 reference devices and the size limit (M-NF-04) |
| [acceptance-tests.md](acceptance-tests.md) | The robustness and offline tests, step by step, with result sheets |
| [interviews.md](interviews.md) | Interview guide: report time, signatures on site, pricing |
| [findings.md](findings.md) | The prioritised findings list (exit criterion) |
| [decisions.md](decisions.md) | The open points the field test decides |

## Goal and exit criteria

From the MARA build plan, Stage 9:

| Exit criterion | How it is measured | Where |
|---|---|---|
| Sync success ≥ 99.5 % without manual conflict handling, over the field-test period | The app's content-free counters (`mobile_telemetry_day`): done ÷ (done + failed), offline runs excluded; conflicts that needed the office counted separately | SQL "field-test report" in [docs/ops/mara.md](../../ops/mara.md#sync-runs-failing-erikamarasyncfailurerate); alert `ErikaMaraSyncFailureRate` |
| 10/10 offline reports complete | Test A1 on the reference devices | [acceptance-tests.md](acceptance-tests.md) |
| All robustness tests pass on the reference devices | Tests A2–A8 | [acceptance-tests.md](acceptance-tests.md) |
| A prioritised list of findings, every blocker fixed | Findings list, blockers linked to their fix | [findings.md](findings.md) |

## Timeline (proposal)

| Week | What |
|---|---|
| −2 | Store accounts, signing keys, GlitchTip project "mara-app", staging with `ERIKA_FEATURE_MARA=true` ([setup.md](setup.md), [distribution.md](distribution.md)) |
| −1 | Acceptance tests A1–A8 on the reference devices in the office; first build to internal testers; data-processing agreements signed |
| 1 | Onboarding of the three firms on site (1–2 h each): sign-up, technicians, first work order together |
| 1–4 | Daily work with the app; weekly call per firm (15 min); telemetry and GlitchTip reviewed twice a week |
| 2 | Mid-term interview per firm ([interviews.md](interviews.md), part 1) |
| 4 | Final interviews (part 2, pricing), findings triage, decisions ([decisions.md](decisions.md)) |

## What the software provides (built in Stage 9)

- **Telemetry without content:** the app counts per day how sync runs and photo uploads end (done, offline, failed; applied, conflict, rejected changes; retried chunks; the longest outbox) and sends the numbers at most hourly (`PUT /api/v1/mobile/devices/me/telemetry`). No IDs, names or texts. Server: table `mobile_telemetry_day` (180 days), Prometheus counters `erika_mara_sync_runs_total`, `erika_mara_push_results_total`, `erika_mara_upload_runs_total`, `erika_mara_upload_retries_total`.
- **Crash reports:** GlitchTip through the Sentry SDK, only when the build has a DSN. Exception types and stack traces only; messages, breadcrumbs, screenshots, user and request data are removed before sending (`mobile/lib/app/crash_reporting.dart`).
- **Store builds:** `.github/workflows/mobile-release.yml` (manual) builds, signs and uploads to Google Play internal testing and TestFlight ([distribution.md](distribution.md)).
- **Size check:** every CI run builds the release APK for 64-bit ARM and fails above 60 MB.
- **Automated robustness tests** (`flutter test`), which the device tests in [acceptance-tests.md](acceptance-tests.md) confirm on hardware:
  - a whole report in flight mode, synced without loss (`report_flow_test.dart`);
  - killed after the server applied a push: nothing lost, nothing twice; random network faults (`sync_test.dart`);
  - 20 photos through a failing network (`photo_upload_test.dart`);
  - storage full while taking a photo: no half file, no record, a clear message (`report_flow_test.dart`);
  - app upgrade with a pending outbox, from every older database version (`database_upgrade_test.dart`);
  - the lost device: revocation deletes the local data (`auth_flow_test.dart`).
