# 0038. MARA sync protocol: outbox, change feed, field versions

- Status: Accepted (status endpoint in MARA Stage 1; protocol in Stage 4)
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

## Implementation (MARA Stage 4)
- **One feed, published by `sync`.** Modules make records syncable by implementing `sync.SyncEntity` (`load`, `view`, `create`, `update`, a `FieldPolicy` per writable field) and call `sync.ChangeFeed.changed` in the changing transaction. In practice they do this from a listener on their own audit events (`WorkOrdersChanged`, `MasterDataChanged`), so office edits in the web app and app edits take the same path. `sync` never reads other modules' tables.
- **`change_log`: one row per record, not a history.**
  - The row holds the sequence number of the record's last change and, per field, `[version, sha256 of the value]`. A field's version is the sequence number of the change that last changed its value. Values are compared by hash, so the feed stores no customer data.
  - A pull returns the records' current state in sequence order.
  - The counter is the workspace's row in `sync_counter`, locked by `INSERT … ON CONFLICT DO UPDATE … RETURNING` until commit.
  - Each record gets its own number, so a page boundary never splits one number.
- **First sync of a workspace.** Records written before the feed existed are entered once, at the first push or pull (`sync_counter.bootstrapped`), because field hashes are computed in code.
- **Entities in Stage 4:**
  - `work_order`: create and update;
  - `customer`: create and update, last writer wins with a note;
  - read-only: `site`, `checklist_template`, `staff_level` (rate only with price visibility) and `article` (net price only with price visibility).
- **Writing from the app.**
  - Technicians create work orders (they are assigned automatically) and customers.
  - On existing work orders they change only `status` and `workNotes`; other fields are rejected with `FIELD_NOT_ALLOWED`.
  - Owners and members may change planning fields from the app too; on a conflict the server's value stays.
  - A technician's new work order sends the office an email.
- **Wire format.** Times are UTC instants and decimals are strings. A field missing from `data` is null.
- **Scope.**
  - Owners and members see every work order; technicians see the ones assigned to them or created by them.
  - Finished work orders (done, cancelled, signed, invoiced) that ended more than 30 days ago leave the phones.
  - There is **no upper limit of 60 days**: a work order that moves into such a window over time causes no change, so it would never arrive.
  - A record that leaves the scope arrives as a removal.
- **No deletable entities in Stage 4.** Work orders are cancelled and customers archived. Deletes came with Stage 5 (see below).
- **Rejections** are stored like successes in `applied_mutation`, so a resend gets the same `REJECTED` answer. Every mutation runs in a savepoint (`PROPAGATION_NESTED`).
- **Notes for the office** go into `sync_conflict` and show on the work order page (`GET /sync-conflicts?entityId=`). There is no customer page view yet.
- **Push messages.**
  - `ChangeFeed` queues one `sync.notify-devices` job per changing transaction, which sends a data-only message to every active phone with a push token (`PUT /mobile/devices/me/push-token`).
  - The FCM sender needs a Firebase project and is open. Until then `LoggingPushSender` is used, and the app registers no token.
- **On the phone (Flutter):**
  - `SyncRecords` holds the server state plus a display state with the pending changes of the `Outbox` on top. The `InboxEntries` table is the conflict inbox.
  - Every local change is one transaction: the record and its outbox entry.
  - Push results and pull pages are applied in one transaction each. An older answer never replaces a newer one.
  - The app syncs on unlock, on return to the foreground, two seconds after a local change and every 30 seconds while it is open.
  - **No background sync while locked.** The database key is released only by the PIN (ADR 0037). Background sync would need a key the OS can release without the user, which is a Stage 11 decision.
- **Tests:**
  - shared vectors `mara-rules/vectors/sync/field-merge.json` (Kotlin `ConflictRules`, Dart `decideField`);
  - `SyncIT` for the protocol rules;
  - `SyncFaultIT`, a seeded random simulation against the real API (lost answers after commit, lost requests, duplicate pushes, replayed pulls, office edits in between, records made on site, plus parallel office writers against a pulling phone);
  - `SyncNetworkFaultIT` through toxiproxy (cut-off answers, connection resets, latency);
  - the app's sync tests on the real encrypted database (including a kill between push and result) and its property test against an in-memory server.
  - Counts: 20 runs per build and 10,000 nightly (`.github/workflows/sync-faults.yml`, 8 shards) for the back end; 40 per build and 10,000 nightly for the app.

## Implementation (MARA Stage 5: reports and deletes)
- **Report records.** The `reports` module syncs `report`, `time_entry`, `material_line`, `aufmass_position` and `checklist_answer`.
  - A report and its records are visible exactly where their work order is (`workorders.WorkOrderDirectory.visibleTo`, the same rule as the work orders).
  - A changed work order (for example, a reassignment) sends its report again, so it reaches new assignees and leaves old ones.
  - Each child record has one author, who changes or deletes it (owners and members may too; `NOT_AUTHOR` otherwise). All fields are last writer wins, with a note.
  - Reports of signed or invoiced work orders are refused (`REPORT_LOCKED`) until Stage 7 adds the database trigger.
  - A read-only `staff_member` entity gives the phones the names of the people on time entries.
- **Derived IDs** (`mara-rules/vectors/ids/derived.json`): UUIDv8 from SHA-256(namespace ‖ name).
  - A report's ID comes from its work order (`report/1`), a checklist answer's from report and item key. So two phones that start the same report offline write to one record.
  - A `CREATE` of a record that already exists is merged like an update made without seeing the record. Fields only settable on create (`workOrderId`, `reportId`, `itemKey`) are dropped.
- **Deletes** (op `DELETE`; vectors `sync/delete.json` and `sync/edit-deleted.json`).
  - Records are deleted softly (`deleted_at`). `SyncEntity.load` marks them with `_deleted`, the feed versions that field, and the phones get a removal.
  - A delete carries the versions of all fields the phone saw. If any field changed since, the delete loses: the record stays and comes back to the phone with a note (field `deleted`, winner `SERVER`).
  - An edit of a record deleted after the phone last saw it brings the record back (field `deleted`, winner `DEVICE`).
  - Deleting what is already gone is `APPLIED`.
- **Earlier answers of the same push count as seen.** A record created and then changed or deleted in one push (or in a resend after a lost answer) is no conflict with itself.
- **Tombstones on the phone.** A record removed on the phone (a confirmed delete or a removal) leaves a tombstone with a version, so a late, older answer can't bring it back. A removal's version is its page's cursor; a confirmed delete's version is the record's last.
- **Protocol version stays 1.** The changes are additive: older apps never send `DELETE` and store unknown entities without showing them.
- **Tests:** the shared vectors in Kotlin and Dart; `ReportsSyncIT`, covering a whole report, derived IDs from two phones, delete versus edit in both orders, scope and reassignment, author rules and locks. The fault simulations now include report records with deletes: `SyncFaultIT` on the server, and on the phone a property test that also replays old pull pages.

## Consequences
- The protocol is ours to maintain, including the signed-report rules, without a vendor dependency.
- The per-workspace counter serialises writers within one workspace. That is fine for firms with 2–15 people; the load test in Stage 11 checks it.
