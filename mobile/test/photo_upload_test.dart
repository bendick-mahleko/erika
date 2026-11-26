import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/media/photo_uploader.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_store.dart';

import 'support/fake_sync_server.dart';
import 'support/fake_tus_server.dart';
import 'support/fakes.dart';

Future<T> _authorized<T>(Future<T> Function(String accessToken) call) => call('token');

/// MARA Stage 6 (M-MO-05, ADR 0040): photo files reach the server with tus, resumed after every
/// interruption, without blocking or duplicating anything.
void main() {
  late AppDatabase db;
  late SyncStore store;
  late FakeSyncServer server;
  late FakeTusServer tus;
  late SyncEngine engine;
  late FakeLocalFiles local;
  late PhotoFiles files;
  late PhotoUploader uploader;

  setUp(() {
    db = AppDatabase(NativeDatabase.memory());
    store = SyncStore(db);
    server = FakeSyncServer(pageSize: 50);
    tus = FakeTusServer(server, random: Random(7));
    engine = SyncEngine(store: store, transport: server, authorized: _authorized);
    local = FakeLocalFiles();
    files = DevicePhotoFiles(local);
    uploader = PhotoUploader(
      store: store,
      files: files,
      transport: tus,
      authorized: _authorized,
      chunkSize: 4096,
    );
  });

  tearDown(() async {
    await db.close();
    local.dir.deleteSync(recursive: true);
  });

  Uint8List photo(int seed, [int size = 20000]) {
    final random = Random(seed);
    return Uint8List.fromList(List.generate(size, (_) => random.nextInt(256)));
  }

  Future<String> take(Uint8List bytes) async {
    final id = 'p-${sha256.convert(bytes).toString().substring(0, 12)}';
    final saved = await files.save(id, bytes);
    await store.create('photo', {
      'reportId': 'r1',
      'category': 'BEFORE',
      'takenAt': '2027-06-01T08:00:00Z',
      'sizeBytes': saved.size,
      'sha256': saved.sha256,
    }, id: id);
    return id;
  }

  test('a photo waits until the server has its record, then its file follows in chunks', () async {
    final id = await take(photo(1));
    expect(await uploader.waiting(), isEmpty, reason: 'the record is not on the server yet');
    expect(await uploader.run(), UploadOutcome.done);
    expect(tus.chunks, 0);

    expect(await engine.sync(), SyncOutcome.done);
    expect((await store.record('photo', id))!.text('state'), 'PENDING_FILE');
    expect(await uploader.run(), UploadOutcome.done);
    expect(tus.completed, [id]);
    expect(tus.chunks, 5, reason: '20,000 bytes in chunks of 4,096');
    await engine.sync();
    expect((await store.record('photo', id))!.text('state'), 'STORED');
    expect(await uploader.waiting(), isEmpty);
  });

  test('20 photos through a network that keeps failing: all arrive, none twice', () async {
    final ids = [for (var i = 0; i < 20; i++) await take(photo(i))];
    await engine.sync();
    tus.failureRate = 0.25;
    var runs = 0;
    while ((await uploader.waiting()).isNotEmpty || tus.completed.length < ids.length) {
      if (++runs > 500) fail('not done after 500 runs');
      await uploader.run();
      try {
        server.failNextPull = false;
        await engine.sync();
      } on ApiProblem {
        // offline for a moment
      }
    }
    expect(tus.completed.toSet(), ids.toSet());
    expect(tus.completed.length, ids.length, reason: 'no file completed twice');
    for (final id in ids) {
      expect(server.data('photo', id)!['state'], 'STORED');
    }
  });

  test('a file the server refuses by its hash is sent again from the start', () async {
    final id = await take(photo(3));
    await engine.sync();
    tus.corruptNext = true;
    expect(await uploader.run(), UploadOutcome.done);
    expect(tus.completed, [id]);
    expect(tus.chunks, 10, reason: 'twice 5 chunks');
  });

  test(
    'a photo deleted before its upload is never sent; offline ends the run without loss',
    () async {
      final kept = await take(photo(4));
      final gone = await take(photo(5));
      await engine.sync();
      await store.delete('photo', gone);
      tus.failureRate = 1;
      expect(await uploader.run(), UploadOutcome.offline);
      tus.failureRate = 0;
      expect(await uploader.run(), UploadOutcome.done);
      expect(tus.completed, [kept]);
    },
  );
}
