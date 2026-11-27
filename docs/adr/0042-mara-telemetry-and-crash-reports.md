# 0042. MARA: telemetry and crash reports without content

- Status: Accepted (MARA Stage 9); the privacy notice text awaits the lawyer
- Date: 2026-11-27

## Context
The field test (MARA build plan, Stage 9) must show that sync succeeds in ≥ 99.5 % of runs without manual conflict handling, and must find crashes on real phones. Failures that never reach the server (no connection, a crash before the request) are invisible to server logs. The app holds customers' names, addresses, photos of their homes, signatures and working times; none of that may leave the phone for diagnostics, and ERIKA uses no third-party analytics (privacy notice §5).

## Decision
- **Counters, not events.** The app counts per local day how sync runs end (done, offline, failed), what the server did with its changes (applied, conflict, rejected), how photo upload runs end, retried chunks and restarted files, and the day's longest outbox. Only these numbers, with the day. No record IDs, names, texts, locations or timings of individual actions.
- **Sent idempotently.** The counters are kept in the encrypted app database for 14 days and sent at most hourly after a successful sync (`PUT /api/v1/mobile/devices/me/telemetry`, bearer token). A day replaces the server's copy for that device and day, so resends change nothing. Counting and sending never break a sync: failures are ignored.
- **Server.** `sync.MobileTelemetry` stores the days in `mobile_telemetry_day` (tenant table, RLS, 180 days, part of the account export) and adds the growth to Prometheus counters (`erika_mara_sync_runs_total{outcome}`, `erika_mara_push_results_total{status}`, `erika_mara_upload_runs_total{outcome}`, `erika_mara_upload_retries_total{kind}`). Alert `ErikaMaraSyncFailureRate`: more than 0.5 % of non-offline runs failed over a day, from 200 runs up.
- **Success rate.** done ÷ (done + failed). Offline runs don't count: a missing connection is expected on building sites and the outbox keeps everything. Conflicts are counted separately; those shown to the office are reviewed.
- **Crash reports.** ERIKA's own GlitchTip (Sentry protocol), project "mara-app", through `sentry_flutter`, only when the build has a DSN. Before sending, the app removes exception messages, the event message, breadcrumbs, user, request and extra data; screenshots, view hierarchy, performance tracing and interaction breadcrumbs are off. What stays: exception types, stack traces, app version, OS and device model. Native crashes (Android/iOS) carry stack traces and device data only.

## Consequences
- The field-test metric and the alert work without any content leaving the phone; the privacy notice names both kinds of data (draft until the lawyer's review).
- Counters can't explain a single failure; for that, GlitchTip (stack traces) and the API log (by device ID, no content) are used.
- A reinstalled app starts its counters again; the server counts a smaller value as new growth, which can only over-count failures, never hide them.
- `sentry_flutter` adds about 1–2 MB to the app; the size check keeps the total under 60 MB.
