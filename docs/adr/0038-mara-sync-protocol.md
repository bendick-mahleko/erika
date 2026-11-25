# 0038. MARA sync protocol: outbox, change feed, field versions

- Status: Proposed (status endpoint in MARA Stage 1; protocol in Stage 4)
- Date: 2026-11-25

## Context
Technicians work offline. Changes must reach the server, and office changes must reach the phones, with no loss, no duplicates and no silent overwrites, on weak connections and with app-store delays between versions (M-NF-01 to M-NF-03; target: ≥ 99.5 % of syncs without manual conflict handling). The MARA design (§4, ADR M-002) chose its own protocol over PowerSync/ElectricSQL or CRDTs. This ADR adopts it, with the details that follow from running inside ERIKA.

## Decision
- **IDs.** Every syncable record gets a UUIDv7 created on the device, so records can be created and referenced offline (M-003).
- **Push.** `POST /api/v1/mobile/sync/push` takes up to 200 outbox mutations in order. Each is applied in its own savepoint.
  - `applied_mutation` remembers mutation IDs for 30 days, so a resend after a timeout changes nothing.
  - Updates carry the `base_version` of each changed field. A field whose server version moved on becomes a conflict.
  - The result per mutation is applied (with the new versions), conflict (with the server value) or rejected (with a stable code, e.g. `REPORT_SIGNED`).
- **Pull.** `GET /api/v1/mobile/sync/pull?cursor=` returns up to 500 records within the user's scope, removals, `nextCursor` and `hasMore`. The device keeps its pending local fields until their push result arrives.
- **Change sequence.** One counter per workspace (`sync_counter`). The writing transaction takes its next number with `UPDATE … RETURNING` on the workspace's row, which locks the row until commit. So within a workspace, sequence order is commit order, and a cursor never skips a change that commits late. This is why there is no global database sequence: concurrent transactions could commit out of order.
- **Office edits go through `sync` too,** so phones see office changes the same way.
- **Conflict rules (design §4).**
  - Child records (times, materials, photos, Aufmaß, checklist answers) have one author and one report, so conflicts are rare.
  - Work order: office wins for planning fields, the technician wins for status and work notes, and the loser's value goes into a conflict note.
  - Customer data: last writer wins, with a note to the office.
  - Signed reports: immutable, so later edits are rejected.
  - Deletes are tombstones, and a delete loses against a concurrent edit.
- **Scope.**
  - Work orders assigned to the user, from 30 days back to 60 days ahead, plus the ones they created, with their children.
  - Reference data for the whole workspace: articles as a delta; rates only if the user may see prices.
  - A record that leaves the scope arrives as a removal, after the device's own changes to it have been pushed.
- **Versions.**
  - Every sync request carries `protocolVersion`, and the server accepts the current and the previous version (`SyncProtocol.CURRENT` and `MINIMUM`).
  - `GET /api/v1/mobile/status` (no token, Stage 1) tells the app the supported versions, the minimum and recommended app versions (`erika.mobile.*`) and the server time, which is used for the clock-skew check.
- **Push notifications.** Data-only "sync now" messages through FCM/APNs, without personal data, sent by a worker job.
- **Tests.**
  - Shared JSON vectors for push/pull in `mara-rules/`.
  - Fault injection with toxiproxy and property-based tests: disconnects, timeouts after commit, duplicate and out-of-order requests, concurrent edits, clock skew. There must be no loss or duplicate in 10,000 runs: a smaller count on every build, the full count nightly.

## Consequences
- The protocol is ours to maintain, including the signed-report rules, without a vendor dependency.
- The per-workspace counter serialises writers within one workspace. That is fine for firms with 2–15 people; the load test in Stage 11 checks it.
