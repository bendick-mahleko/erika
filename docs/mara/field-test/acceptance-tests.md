# Acceptance tests on the reference devices

Run every test on each reference device ([devices.md](devices.md)) with a release build from the release workflow, against staging, in a test workspace (not a firm's). Each test has an automated counterpart in `mobile/test/` that runs in CI; the device run proves it on real hardware, storage and radios.

**Pass** means: after the final sync, the office web app shows exactly what was recorded on the phone (every time entry, material, Aufmaß position, checklist answer, photo and signature, nothing twice), and the outbox on the phone is empty.

How to check "nothing lost, nothing twice" quickly: before the test, write down what you record (the sheet below); afterwards compare it with the work order page in the web app.

## A1 – 10 complete reports offline (exit criterion: 10/10)

Automated: `report_flow_test.dart` "a whole report in flight mode, then synced".

1. In the office: 10 work orders for the test technician, each with a checklist that has a photo item.
2. On the phone: open the app online once (work orders arrive), then switch on flight mode.
3. For each work order: start/stop a work time and a travel time, 2 materials (one from the article list, one free text), 1 Aufmaß position, every checklist item, 3 photos, the work description, the customer's signature (sign yourself with a test name); the customer PDF is created on the phone.
4. Put the phone away for at least 30 minutes, still in flight mode (the OS may stop the app).
5. Switch flight mode off, open the app, wait for "synchronised".
6. Office: all 10 reports signed, PDFs archived, 30 photos stored, all values as recorded.

| Device | Reports complete | Photos stored | Problems | Date, tester |
|---|---|---|---|---|
| D1 | /10 | /30 | | |
| D2 | /10 | /30 | | |
| D3 | /10 | /30 | | |
| D4 | /10 | /30 | | |
| D5 | /10 | /30 | | |

## A2 – Killed during work

Automated: `sync_test.dart` "killed after the server applied a push", random faults.

1. Offline: record times, materials and 2 photos for a work order.
2. Force-stop the app (Android: Settings → Apps → Force stop; iOS: swipe it away) right after saving a material.
3. Reopen, unlock with the PIN: everything recorded is there.
4. Go online, and while "synchronising" is shown, force-stop again. Reopen, sync.
5. Office: everything once.

## A3 – Battery out

1. Offline: record a report with photos, without signing.
2. Pull the battery (rugged device D2) or let the phone run flat (others).
3. Charge, start, unlock: everything is there. Sign, sync; office: complete.

## A4 – Storage full

Automated: `report_flow_test.dart` "storage full: the photo is not kept, nothing is recorded".

1. Fill the phone's storage (copy large videos) until less than 50 MB are free.
2. Take photos until the app says the photo couldn't be saved. Expected: the message; no half photo in the report.
3. Record times and materials: they still save (small database writes), or the app reports the problem without crashing. Note which.
4. Free storage; take a photo again: works. Sync; office: complete, no broken photo.

## A5 – OS upgrade with unsent changes

1. On a device with a pending OS update: record a report offline, don't sync.
2. Install the OS update, restart, open the app, unlock: everything there. Sync; office: complete.

## A6 – App upgrade with a pending outbox

Automated: `database_upgrade_test.dart` (upgrades from every older database version keep the outbox).

1. Install build N. Record a report offline, don't sync.
2. Install build N+1 over it (from internal testing / TestFlight), still offline.
3. Open, unlock: everything there; no new sign-in needed. Go online, sync; office: complete.

## A7 – Lost device

Automated: `auth_flow_test.dart` "lost device: revoked in the web app, the next contact deletes the local data".

1. Record a report offline on the phone (unsent changes are lost by design; tell firms to sync before reporting a loss if they can).
2. Office, as owner: Settings → Devices → revoke the phone.
3. Phone online, open the app: it shows that the device was signed out and deletes its data; the photo folder is empty (check with a file manager / Files app where possible).
4. A new sign-in works and fetches the work orders again.

## A8 – Poor network

Automated: `photo_upload_test.dart` "20 photos through a network that keeps failing", `PhotoNetworkFaultIT`, `SyncNetworkFaultIT`.

1. In a basement or a lift with weak reception (or Android developer options: network throttling), take 20 photos for one report and sync.
2. Walk in and out of reception for 10 minutes.
3. Office: 20 photos, none twice, all stored.

## Results

| Test | D1 | D2 | D3 | D4 | D5 |
|---|---|---|---|---|---|
| A1 Offline reports | | | | | |
| A2 Killed | | | | | |
| A3 Battery out | | | | | |
| A4 Storage full | | | | | |
| A5 OS upgrade | | | | | |
| A6 App upgrade | | | | | |
| A7 Lost device | | | | | |
| A8 Poor network | | | | | |

Anything that fails goes into [findings.md](findings.md) as a blocker.
