import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/media/photo_uploader.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_store.dart';
import 'package:mara/data/telemetry/telemetry.dart';

import 'support/fake_sync_server.dart';
import 'support/fake_tus_server.dart';

Future<T> _authorized<T>(Future<T> Function(String accessToken) call) => call('token');

class _Transport implements TelemetryTransport {
  final reports = <Map<String, Map<String, int>>>[];
  var failing = false;

  @override
  Future<void> send(String accessToken, Map<String, Map<String, int>> days) async {
    if (failing) throw const SocketException('offline');
    reports.add(days);
  }
}

/// MARA Stage 9: the content-free counters behind the field-test target (sync success ≥ 99.5 %).
void main() {
  late AppDatabase db;
  late DateTime now;
  late Telemetry telemetry;

  setUp(() {
    db = AppDatabase(DatabaseConnection(NativeDatabase.memory(), closeStreamsSynchronously: true));
    now = DateTime(2027, 5, 10, 9);
    telemetry = Telemetry(db, clock: () => now);
  });
  tearDown(() => db.close());

  test('counts per local day, keeps the maximum, forgets days after two weeks', () async {
    await telemetry.count(TelemetryCounter.syncDone);
    await telemetry.count(TelemetryCounter.syncDone, 2);
    await telemetry.max(TelemetryCounter.outboxMax, 7);
    await telemetry.max(TelemetryCounter.outboxMax, 3);
    now = now.add(const Duration(days: 1));
    await telemetry.count(TelemetryCounter.syncOffline);
    expect(await telemetry.stored(), {
      '2027-05-10': {'syncDone': 3, 'outboxMax': 7},
      '2027-05-11': {'syncOffline': 1},
    });
    now = now.add(const Duration(days: 20));
    await telemetry.count(TelemetryCounter.syncFailed);
    expect((await telemetry.stored()).keys, ['2027-05-31']);
  });

  test('sent at most once an hour; a failed send is retried later and breaks nothing', () async {
    final transport = _Transport();
    await telemetry.count(TelemetryCounter.syncDone);
    transport.failing = true;
    expect(await telemetry.sendIfDue(transport, _authorized), isFalse);
    transport.failing = false;
    expect(await telemetry.sendIfDue(transport, _authorized), isTrue);
    expect(await telemetry.sendIfDue(transport, _authorized), isFalse, reason: 'within the hour');
    now = now.add(const Duration(minutes: 61));
    expect(await telemetry.sendIfDue(transport, _authorized), isTrue);
    expect(transport.reports, hasLength(2));
    expect(transport.reports.first, {
      '2027-05-10': {'syncDone': 1},
    });
  });

  test('the sync engine counts how runs end and what the server did with the changes', () async {
    final server = FakeSyncServer();
    final store = SyncStore(db);
    final engine = SyncEngine(
      store: store,
      transport: server,
      authorized: _authorized,
      telemetry: telemetry,
    );
    await store.create('time_entry', {'reportId': 'r1', 'kind': 'WORK'});
    await store.create('time_entry', {'reportId': 'r1', 'kind': 'TRAVEL'});
    expect(await engine.sync(), SyncOutcome.done);
    server.offline = true;
    await store.create('time_entry', {'reportId': 'r1', 'kind': 'WORK'});
    expect(await engine.sync(), SyncOutcome.offline);
    final day = (await telemetry.stored()).values.single;
    expect(day, {'outboxMax': 2, 'syncDone': 1, 'pushApplied': 2, 'syncOffline': 1});
  });

  test('the uploader counts runs and files sent again after a refused hash', () async {
    final server = FakeSyncServer();
    final store = SyncStore(db);
    final engine = SyncEngine(store: store, transport: server, authorized: _authorized);
    final tus = FakeTusServer(server);
    final files = MemoryPhotoFiles();
    final uploader = PhotoUploader(
      store: store,
      files: files,
      transport: tus,
      authorized: _authorized,
      chunkSize: 4096,
      telemetry: telemetry,
    );
    final bytes = Uint8List.fromList(List.generate(10000, (i) => i % 251));
    final saved = await files.save('p1', bytes);
    await store.create('photo', {
      'reportId': 'r1',
      'category': 'BEFORE',
      'takenAt': '2027-05-10T07:00:00Z',
      'sizeBytes': saved.size,
      'sha256': saved.sha256,
    }, id: 'p1');
    await engine.sync();
    tus.corruptNext = true;
    expect(await uploader.run(), UploadOutcome.done);
    expect(tus.completed, ['p1']);
    final day = (await telemetry.stored()).values.single;
    expect(day['uploadDone'], 1);
    expect(day['uploadRestarts'], 1);
  });
}
