import 'dart:convert';

import 'package:drift/drift.dart';

import '../../domain/uuid7.dart';
import '../local/database.dart';
import 'sync_api.dart';

/// A record as the screens see it: the server state with the phone's pending changes on top.
class LocalRecord {
  const LocalRecord({
    required this.entity,
    required this.id,
    required this.version,
    required this.data,
    required this.pending,
  });

  final String entity;
  final String id;
  final int version;
  final Map<String, dynamic> data;

  /// Changes of this record still in the outbox.
  final bool pending;

  String? text(String field) => data[field] as String?;

  /// Deleted here and waiting for the server to confirm: the screens leave it out.
  bool get deleted => data[SyncStore.deletedField] == true;
}

/// The phone's side of the sync protocol on the encrypted database (ADR 0038). Every local
/// change is one transaction: the record and its outbox entry. Pulls and push results never
/// overwrite pending local fields; those stay on top until their own push result arrives.
class SyncStore {
  SyncStore(this.db, {String Function()? newId, DateTime Function()? now})
    : _newId = newId ?? uuid7,
      _now = now ?? DateTime.now;

  final AppDatabase db;
  final String Function() _newId;
  final DateTime Function() _now;

  static const cursorKey = 'sync.cursor';

  /// Marks a record whose delete is still in the outbox (never sent to the server as a field).
  static const deletedField = '_deleted';
  static const lastSyncKey = 'sync.lastSuccess';

  /// A record made on the phone (a work order or customer on site, M-MO-02). Returns its ID.
  Future<String> create(String entity, Map<String, dynamic> values, {String? id}) async {
    final recordId = id ?? _newId();
    await db.transaction(() async {
      await db
          .into(db.syncRecords)
          .insert(
            SyncRecordsCompanion.insert(entity: entity, id: recordId, data: jsonEncode(values)),
          );
      await db
          .into(db.outbox)
          .insert(
            OutboxCompanion.insert(
              mutationId: _newId(),
              entity: entity,
              entityId: recordId,
              op: 'CREATE',
              changes: jsonEncode(values),
              createdAt: _now(),
            ),
          );
    });
    return recordId;
  }

  /// Changes [values] of a record; the change waits in the outbox until it is pushed.
  Future<void> change(String entity, String id, Map<String, dynamic> values) async {
    await db.transaction(() async {
      final row = await _row(entity, id);
      if (row == null) throw StateError('no local $entity $id');
      final versions = _intMap(row.fieldVersions);
      await db
          .into(db.outbox)
          .insert(
            OutboxCompanion.insert(
              mutationId: _newId(),
              entity: entity,
              entityId: id,
              op: 'UPDATE',
              changes: jsonEncode(values),
              baseVersions: Value(jsonEncode({for (final k in values.keys) k: versions[k] ?? 0})),
              createdAt: _now(),
            ),
          );
      final data = Map<String, dynamic>.from(jsonDecode(row.data) as Map)..addAll(values);
      await (db.update(db.syncRecords)..where((r) => r.entity.equals(entity) & r.id.equals(id)))
          .write(SyncRecordsCompanion(data: Value(jsonEncode(data))));
    });
  }

  /// A record whose ID two phones derive alike (a report, a checklist answer, ADR 0038): made
  /// here with [createValues] if the phone doesn't have it yet, else changed by [changes]
  /// (nothing when empty). Check and write are one transaction, so two quick taps make one.
  Future<void> put(
    String entity,
    String id, {
    required Map<String, dynamic> createValues,
    Map<String, dynamic> changes = const {},
  }) => db.transaction(() async {
    if (await _row(entity, id) != null) {
      if (changes.isNotEmpty) await change(entity, id, changes);
    } else {
      await create(entity, {...createValues, ...changes}, id: id);
    }
  });

  /// Deletes a record (a time entry, a material line, an Aufmaß position). The delete waits in
  /// the outbox with the versions of all fields the phone saw: a delete loses against an edit
  /// made meanwhile on another device, and the record comes back with a note.
  Future<void> delete(String entity, String id) async {
    await db.transaction(() async {
      final row = await _row(entity, id);
      if (row == null) return;
      await db
          .into(db.outbox)
          .insert(
            OutboxCompanion.insert(
              mutationId: _newId(),
              entity: entity,
              entityId: id,
              op: 'DELETE',
              changes: '{}',
              baseVersions: Value(row.fieldVersions),
              createdAt: _now(),
            ),
          );
      await _recompute(entity, id);
    });
  }

  /// The oldest pending changes, in order.
  Future<List<MutationDto>> pending({int limit = 200}) async {
    final rows =
        await (db.select(db.outbox)
              ..orderBy([(o) => OrderingTerm.asc(o.seq)])
              ..limit(limit))
            .get();
    return [
      for (final o in rows)
        MutationDto(
          mutationId: o.mutationId,
          entity: o.entity,
          entityId: o.entityId,
          op: o.op,
          values: Map<String, dynamic>.from(jsonDecode(o.changes) as Map),
          baseVersions: _intMap(o.baseVersions),
        ),
    ];
  }

  /// Applies the server's answers. A result for an entry no longer in the outbox (a resend
  /// whose first answer was already applied) changes nothing.
  Future<void> applyPushResults(List<MutationResultDto> results) async {
    await db.transaction(() async {
      for (final r in results) {
        final entry = await (db.select(
          db.outbox,
        )..where((o) => o.mutationId.equals(r.mutationId))).getSingleOrNull();
        if (entry == null) continue;
        await (db.delete(db.outbox)..where((o) => o.mutationId.equals(r.mutationId))).go();
        if (entry.op == 'DELETE' && r.status == 'APPLIED') {
          await _forget(entry.entity, entry.entityId, null);
          continue;
        }
        final record = r.record;
        if (record != null) await _storeServer(record);
        for (final c in r.conflicts) {
          await _inbox(
            entry.entity,
            entry.entityId,
            'CONFLICT',
            field: c.field,
            winner: c.winner,
            serverValue: c.serverValue,
            deviceValue: c.deviceValue,
          );
        }
        if (r.status == 'REJECTED') {
          await _inbox(entry.entity, entry.entityId, 'REJECTED', code: r.code);
        }
        // Without the change, the record falls back to the server state plus what is still pending.
        await _recompute(entry.entity, entry.entityId);
      }
    });
  }

  /// Applies one pull page and moves the cursor, in one transaction.
  Future<void> applyPull(PullPage page) async {
    await db.transaction(() async {
      for (final record in page.records) {
        await _storeServer(record);
        await _recompute(record.entity, record.id);
      }
      for (final (entity, id) in page.removals) {
        // A record with pending changes stays until they are pushed (and refused).
        if (await _hasPending(entity, id)) continue;
        await _forget(entity, id, int.tryParse(page.nextCursor));
      }
      await db.putSetting(cursorKey, page.nextCursor);
    });
  }

  Future<String> cursor() async => await db.setting(cursorKey) ?? '';

  Future<LocalRecord?> record(String entity, String id) async {
    final row = await _row(entity, id);
    return row == null ? null : _local(row, await _hasPending(entity, id));
  }

  /// All records of [entity], updated whenever they change.
  Stream<List<LocalRecord>> watch(String entity) {
    final query = db.select(db.syncRecords)..where((r) => r.entity.equals(entity));
    return query.watch().asyncMap((rows) async {
      final pendingIds = (await (db.select(
        db.outbox,
      )..where((o) => o.entity.equals(entity))).get()).map((o) => o.entityId).toSet();
      return [for (final r in rows) _local(r, pendingIds.contains(r.id))];
    });
  }

  Stream<LocalRecord?> watchOne(String entity, String id) =>
      (db.select(db.syncRecords)..where((r) => r.entity.equals(entity) & r.id.equals(id)))
          .watchSingleOrNull()
          .asyncMap((row) async => row == null ? null : _local(row, await _hasPending(entity, id)));

  Stream<int> watchPendingCount() => db.select(db.outbox).watch().map((rows) => rows.length);

  Stream<List<InboxEntry>> watchInbox() =>
      (db.select(db.inboxEntries)..orderBy([(e) => OrderingTerm.desc(e.id)])).watch();

  Future<void> markInboxSeen() =>
      db.update(db.inboxEntries).write(const InboxEntriesCompanion(seen: Value(true)));

  /// Removes a record and leaves a tombstone. Its version is the removal's page cursor, or the
  /// record's last version for a delete confirmed by the server ([version] null).
  Future<void> _forget(String entity, String id, int? version) async {
    final row = await _row(entity, id);
    if (await _hasPending(entity, id)) {
      await _recompute(entity, id);
      return;
    }
    await (db.delete(db.syncRecords)..where((r) => r.entity.equals(entity) & r.id.equals(id))).go();
    final stone = version ?? row?.version ?? 0;
    final old = await _tombstone(entity, id);
    if (old == null || old < stone) {
      await db
          .into(db.tombstones)
          .insertOnConflictUpdate(
            TombstonesCompanion.insert(entity: entity, id: id, version: stone),
          );
    }
  }

  Future<int?> _tombstone(String entity, String id) async => (await (db.select(
    db.tombstones,
  )..where((t) => t.entity.equals(entity) & t.id.equals(id))).getSingleOrNull())?.version;

  Future<void> _storeServer(SyncRecordDto record) async {
    final stone = await _tombstone(record.entity, record.id);
    // Removed after this state was sent (a late, replayed answer): stays removed.
    if (stone != null && stone >= record.version) return;
    if (stone != null) {
      await (db.delete(
        db.tombstones,
      )..where((t) => t.entity.equals(record.entity) & t.id.equals(record.id))).go();
    }
    final existing = await _row(record.entity, record.id);
    // An answer can arrive after a newer one (a replayed pull): keep the newest.
    if (existing != null && existing.serverData != null && existing.version > record.version) {
      return;
    }
    await db
        .into(db.syncRecords)
        .insertOnConflictUpdate(
          SyncRecordsCompanion.insert(
            entity: record.entity,
            id: record.id,
            version: Value(record.version),
            fieldVersions: Value(jsonEncode(record.fieldVersions)),
            serverData: Value(jsonEncode(record.data)),
            data: jsonEncode(record.data),
          ),
        );
  }

  /// data = server state + pending outbox changes of this record, in order.
  Future<void> _recompute(String entity, String id) async {
    final row = await _row(entity, id);
    if (row == null) return;
    final pending =
        await (db.select(db.outbox)
              ..where((o) => o.entity.equals(entity) & o.entityId.equals(id))
              ..orderBy([(o) => OrderingTerm.asc(o.seq)]))
            .get();
    if (row.serverData == null && pending.isEmpty) {
      // Made here and refused by the server: it never existed there.
      await (db.delete(
        db.syncRecords,
      )..where((r) => r.entity.equals(entity) & r.id.equals(id))).go();
      return;
    }
    final data = row.serverData == null
        ? <String, dynamic>{}
        : Map<String, dynamic>.from(jsonDecode(row.serverData!) as Map);
    for (final o in pending) {
      if (o.op == 'DELETE') {
        data[deletedField] = true;
      } else {
        data.addAll(Map<String, dynamic>.from(jsonDecode(o.changes) as Map));
      }
    }
    await (db.update(db.syncRecords)..where((r) => r.entity.equals(entity) & r.id.equals(id)))
        .write(SyncRecordsCompanion(data: Value(jsonEncode(data))));
  }

  Future<void> _inbox(
    String entity,
    String entityId,
    String kind, {
    String? field,
    String? code,
    String? winner,
    Object? serverValue,
    Object? deviceValue,
  }) => db
      .into(db.inboxEntries)
      .insert(
        InboxEntriesCompanion.insert(
          entity: entity,
          entityId: entityId,
          kind: kind,
          field: Value(field),
          code: Value(code),
          winner: Value(winner),
          serverValue: Value(serverValue == null ? null : jsonEncode(serverValue)),
          deviceValue: Value(deviceValue == null ? null : jsonEncode(deviceValue)),
          createdAt: _now(),
        ),
      );

  Future<SyncRecord?> _row(String entity, String id) => (db.select(
    db.syncRecords,
  )..where((r) => r.entity.equals(entity) & r.id.equals(id))).getSingleOrNull();

  Future<bool> _hasPending(String entity, String id) async =>
      (await (db.select(db.outbox)
                ..where((o) => o.entity.equals(entity) & o.entityId.equals(id))
                ..limit(1))
              .get())
          .isNotEmpty;

  LocalRecord _local(SyncRecord row, bool pending) => LocalRecord(
    entity: row.entity,
    id: row.id,
    version: row.version,
    data: Map<String, dynamic>.from(jsonDecode(row.data) as Map),
    pending: pending,
  );

  static Map<String, int> _intMap(String json) =>
      (jsonDecode(json) as Map<String, dynamic>).map((k, v) => MapEntry(k, (v as num).toInt()));
}
