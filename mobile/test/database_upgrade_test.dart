import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/local/database_key.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_store.dart';

import 'support/fake_sync_server.dart';

Future<T> _authorized<T>(Future<T> Function(String accessToken) call) => call('token');

/// MARA Stage 9 robustness test "app upgrade with a pending outbox": a phone with changes that
/// never reached the server installs a newer app. The encrypted database is upgraded in place;
/// nothing waiting is lost and it is sent after the upgrade. Older schemas are made by taking
/// the current one apart again, so the test uses exactly the tables drift creates.
///
/// A new schema version needs a case here (see the last test).
void main() {
  late Directory dir;
  late File file;
  late String key;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('mara-upgrade-');
    file = File('${dir.path}/mara.sqlite');
    key = newDatabaseKey();
  });
  tearDown(() => dir.deleteSync(recursive: true));

  /// A database as schema [version] left it, with whatever [fill] writes.
  Future<void> legacy(int version, Future<void> Function(AppDatabase db) fill) async {
    final db = AppDatabase.openEncrypted(file, key);
    await fill(db);
    if (version < 3) await db.customStatement('DROP TABLE tombstones');
    if (version < 2) {
      await db.customStatement('DROP TABLE sync_records');
      await db.customStatement('DROP TABLE outbox');
      await db.customStatement('DROP TABLE inbox_entries');
    }
    await db.customStatement('PRAGMA user_version = $version');
    await db.close();
  }

  test('from version 2 (Stage 4): the pending outbox survives and is sent afterwards', () async {
    await legacy(2, (db) async {
      final store = SyncStore(db);
      await store.create('time_entry', {'reportId': 'r1', 'kind': 'WORK'}, id: 't1');
      await store.change('time_entry', 't1', {'note': 'mit Azubi'});
      await db.putSetting(SyncStore.cursorKey, 'c-42');
    });

    final db = AppDatabase.openEncrypted(file, key);
    addTearDown(db.close);
    final store = SyncStore(db);
    expect(await store.pendingCount(), 2, reason: 'nothing waiting was lost');
    expect(await store.cursor(), 'c-42');
    expect((await store.record('time_entry', 't1'))!.text('note'), 'mit Azubi');
    // The new table works: a delete leaves its tombstone.
    final server = FakeSyncServer();
    expect(
      await SyncEngine(store: store, transport: server, authorized: _authorized).sync(),
      SyncOutcome.done,
    );
    expect(server.data('time_entry', 't1')!['note'], 'mit Azubi');
    expect(await store.pendingCount(), 0);
    await store.delete('time_entry', 't1');
    expect(
      await SyncEngine(store: store, transport: server, authorized: _authorized).sync(),
      SyncOutcome.done,
    );
    expect(server.data('time_entry', 't1'), isNull);
  });

  test('from version 1 (Stage 1): settings stay, the sync tables are created', () async {
    await legacy(1, (db) => db.putSetting('media.hintShown', 'true'));
    final db = AppDatabase.openEncrypted(file, key);
    addTearDown(db.close);
    expect(await db.setting('media.hintShown'), 'true');
    final store = SyncStore(db);
    await store.create('time_entry', {'reportId': 'r1', 'kind': 'WORK'});
    expect(await store.pendingCount(), 1);
  });

  test('every schema version has an upgrade case here', () {
    final db = AppDatabase.openEncrypted(file, key);
    addTearDown(db.close);
    expect(
      db.schemaVersion,
      3,
      reason: 'a new schema version needs an upgrade test with a pending outbox in this file',
    );
  });
}
