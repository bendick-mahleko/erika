import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/local/database_key.dart';
import 'package:mara/data/sync/sync_api.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_store.dart';

import 'support/fake_sync_server.dart';

Future<T> _authorized<T>(Future<T> Function(String accessToken) call) => call('token');

/// The app's side of the sync protocol (ADR 0038, M-NF-01 to M-NF-03), on the real encrypted
/// database, against an in-memory server.
void main() {
  late Directory dir;
  late File file;
  late String key;
  late AppDatabase db;
  late SyncStore store;
  late FakeSyncServer server;
  late SyncEngine engine;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('mara-sync-');
    file = File('${dir.path}/mara.sqlite');
    key = newDatabaseKey();
    db = AppDatabase.openEncrypted(file, key);
    store = SyncStore(db);
    server = FakeSyncServer();
    engine = SyncEngine(store: store, transport: server, authorized: _authorized);
  });

  tearDown(() async {
    await db.close();
    dir.deleteSync(recursive: true);
  });

  test('office records arrive in pages; local changes go out and come back confirmed', () async {
    for (var i = 0; i < 12; i++) {
      server.officeWrite('work_order', 'wo-$i', {'title': 'Auftrag $i', 'status': 'PLANNED'});
    }
    expect(await engine.sync(), SyncOutcome.done);
    expect((await store.watch('work_order').first).length, 12);

    await store.change('work_order', 'wo-3', {
      'status': 'IN_PROGRESS',
      'workNotes': 'Kessel entkalkt',
    });
    final local = await store.record('work_order', 'wo-3');
    expect(local!.data['status'], 'IN_PROGRESS', reason: 'shown at once');
    expect(local.pending, isTrue);

    expect(await engine.sync(), SyncOutcome.done);
    expect(server.data('work_order', 'wo-3')!['workNotes'], 'Kessel entkalkt');
    expect((await store.record('work_order', 'wo-3'))!.pending, isFalse);
  });

  test('a pull never overwrites a pending local change', () async {
    server.officeWrite('work_order', 'wo', {'title': 'Bad', 'status': 'PLANNED'});
    await engine.sync();
    await store.change('work_order', 'wo', {'status': 'DONE'});
    server.officeWrite('work_order', 'wo', {'title': 'Bad und Küche'});
    server.failBeforeNextPush = true; // offline for the push, online for the pull afterwards
    expect(await engine.sync(), SyncOutcome.offline);
    final page = await server.pull('t', await store.cursor());
    await store.applyPull(page);
    final local = (await store.record('work_order', 'wo'))!;
    expect(local.data['title'], 'Bad und Küche', reason: 'the office change arrives');
    expect(local.data['status'], 'DONE', reason: 'the pending change stays on top');
  });

  test('conflicts land in the inbox; the field rule decides', () async {
    server.officeWrite('work_order', 'wo', {'title': 'Lüftung', 'status': 'PLANNED'});
    await engine.sync();
    await store.change('work_order', 'wo', {'status': 'DONE', 'title': 'Vom Handy'});
    server.officeWrite('work_order', 'wo', {'status': 'CANCELLED', 'title': 'Lüftung Halle 2'});
    await engine.sync();
    final local = (await store.record('work_order', 'wo'))!;
    expect(local.data['status'], 'DONE', reason: 'the technician wins the status');
    expect(local.data['title'], 'Lüftung Halle 2', reason: 'the office wins planning');
    final inbox = await store.watchInbox().first;
    expect(inbox.map((e) => '${e.field}:${e.winner}').toSet(), {'status:DEVICE', 'title:SERVER'});
  });

  test('a create the server refuses disappears and is explained', () async {
    await store.create('invoice', {'x': 1}, id: 'inv-1');
    await engine.sync();
    expect(await store.record('invoice', 'inv-1'), isNull);
    final inbox = await store.watchInbox().first;
    expect(inbox.single.kind, 'REJECTED');
    expect(inbox.single.code, 'UNKNOWN_ENTITY');
  });

  test('an older answer arriving late (replayed pull) does not undo a newer one', () async {
    server.officeWrite('work_order', 'wo', {'title': 'Alt'});
    final old = await server.pull('t', '');
    server.officeWrite('work_order', 'wo', {'title': 'Neu'});
    await engine.sync();
    await store.applyPull(old);
    expect((await store.record('work_order', 'wo'))!.data['title'], 'Neu');
  });

  test('killed after the server applied a push: nothing lost, nothing applied twice', () async {
    server.officeWrite('customer', 'c1', {'name': 'Familie Keller'});
    await engine.sync();
    final created = await store.create('work_order', {
      'customerId': 'c1',
      'title': 'Rohrbruch',
      'status': 'PLANNED',
    });
    await store.change('customer', 'c1', {'phone': '0341 123456'});

    server.dropNextAnswer = true; // the server commits, the app dies before it sees the answer
    expect(await engine.sync(), SyncOutcome.offline);
    await db.close();

    // The app starts again on the same encrypted file.
    db = AppDatabase.openEncrypted(file, key);
    store = SyncStore(db);
    engine = SyncEngine(store: store, transport: server, authorized: _authorized);
    expect((await store.pending()).length, 2, reason: 'the outbox survived');
    expect(await engine.sync(), SyncOutcome.done);
    expect(await store.pending(), isEmpty);
    expect(server.appliedLog.length, 2, reason: 'the resend was answered from the log');
    expect(server.data('work_order', created)!['title'], 'Rohrbruch');
    expect(server.data('customer', 'c1')!['phone'], '0341 123456');
  });

  test(
    'random faults: everything arrives exactly once and phone and server agree',
    () async {
      final runs = int.tryParse(Platform.environment['SYNC_FAULT_RUNS'] ?? '') ?? 40;
      final seed =
          int.tryParse(Platform.environment['SYNC_FAULT_SEED'] ?? '') ??
          DateTime.now().microsecondsSinceEpoch;
      for (var run = 0; run < runs; run++) {
        final random = Random(seed + run);
        final made = <String>{};
        for (var i = 0; i < 5; i++) {
          server.officeWrite('work_order', 'r$run-$i', {
            'title': 'Auftrag $i',
            'status': 'PLANNED',
          });
        }
        await engine.sync();
        for (var step = 0; step < 30; step++) {
          final orders = (await store.watch('work_order').first).map((r) => r.id).toList();
          switch (random.nextInt(7)) {
            case 0:
            case 1:
              final id = orders[random.nextInt(orders.length)];
              await store.change(
                'work_order',
                id,
                random.nextBool()
                    ? {
                        'status': ['PLANNED', 'IN_PROGRESS', 'DONE'][random.nextInt(3)],
                      }
                    : {'workNotes': randomText(random)},
              );
            case 2:
              made.add(
                await store.create('work_order', {
                  'title': randomText(random),
                  'status': 'PLANNED',
                }),
              );
            case 3:
              final id = 'r$run-${random.nextInt(5)}';
              server.officeWrite(
                'work_order',
                id,
                random.nextBool() ? {'title': randomText(random)} : {'status': 'IN_PROGRESS'},
              );
            case 4:
              server.dropNextAnswer = true;
              await engine.sync();
            case 5:
              server.failBeforeNextPush = random.nextBool();
              server.failNextPull = !server.failBeforeNextPush;
              await engine.sync();
            case 6:
              await engine.sync();
          }
        }
        server
          ..dropNextAnswer = false
          ..failBeforeNextPush = false
          ..failNextPull = false;
        expect(await engine.sync(), SyncOutcome.done, reason: 'seed ${seed + run}');
        expect(await store.pending(), isEmpty, reason: 'seed ${seed + run}');
        expect(
          server.appliedLog.toSet().length,
          server.appliedLog.length,
          reason: 'no mutation applied twice; seed ${seed + run}',
        );
        for (final id in made) {
          expect(
            server.data('work_order', id),
            isNotNull,
            reason: 'made on the phone, arrived; seed ${seed + run}',
          );
        }
        for (final (entity, id, data) in server.all) {
          final local = await store.record(entity, id);
          expect(local?.data, data, reason: '$entity $id agrees; seed ${seed + run}');
        }
      }
    },
    timeout: const Timeout(Duration(hours: 2)),
  );

  test('a delete hides the record at once and removes it once the server confirms', () async {
    server.officeWrite('material_line', 'm1', {
      'reportId': 'r',
      'description': 'Rohr',
      'quantity': '3',
    });
    await engine.sync();
    final oldPage = await server.pull('t', '0');
    await store.delete('material_line', 'm1');
    expect((await store.record('material_line', 'm1'))!.deleted, isTrue, reason: 'hidden at once');
    expect(await engine.sync(), SyncOutcome.done);
    expect(server.data('material_line', 'm1'), isNull);
    expect(await store.record('material_line', 'm1'), isNull);
    // A replayed, older answer doesn't bring it back (tombstone).
    await store.applyPull(oldPage);
    expect(await store.record('material_line', 'm1'), isNull);
  });

  test('a delete loses against an edit made meanwhile; the inbox says so', () async {
    server.officeWrite('material_line', 'm1', {
      'reportId': 'r',
      'description': 'Rohr',
      'quantity': '3',
    });
    await engine.sync();
    server.officeWrite('material_line', 'm1', {'quantity': '4'});
    await store.delete('material_line', 'm1');
    expect(await engine.sync(), SyncOutcome.done);
    final local = (await store.record('material_line', 'm1'))!;
    expect(local.deleted, isFalse);
    expect(local.data['quantity'], '4');
    final inbox = await store.watchInbox().first;
    expect(inbox.single.field, 'deleted');
    expect(inbox.single.winner, 'SERVER');
  });

  test('made and deleted before the next sync: nothing stays anywhere', () async {
    final id = await store.create('time_entry', {
      'reportId': 'r',
      'startedAt': '2027-05-10T07:00:00Z',
    });
    await store.delete('time_entry', id);
    expect(await engine.sync(), SyncOutcome.done);
    expect(server.data('time_entry', id), isNull);
    expect(await store.record('time_entry', id), isNull);
    expect(await store.watchInbox().first, isEmpty, reason: 'no conflict with its own create');
  });

  test('records with derived IDs are made once and changed afterwards', () async {
    await store.put(
      'checklist_answer',
      'a1',
      createValues: {'reportId': 'r', 'itemKey': 'k'},
      changes: {'value': 'YES'},
    );
    await store.put(
      'checklist_answer',
      'a1',
      createValues: {'reportId': 'r', 'itemKey': 'k'},
      changes: {'value': 'NO'},
    );
    final ops = (await store.pending()).map((m) => m.op).toList();
    expect(ops, ['CREATE', 'UPDATE']);
    expect((await store.pending()).last.values, {
      'value': 'NO',
    }, reason: 'create-only fields are not changed');
    expect(await engine.sync(), SyncOutcome.done);
    expect(server.data('checklist_answer', 'a1')!['value'], 'NO');
  });

  test(
    'random faults on report records with deletes: nothing lost, nothing duplicated',
    () async {
      final runs = int.tryParse(Platform.environment['SYNC_FAULT_RUNS'] ?? '') ?? 40;
      final seed =
          int.tryParse(Platform.environment['SYNC_FAULT_SEED'] ?? '') ??
          DateTime.now().microsecondsSinceEpoch;
      for (var run = 0; run < runs; run++) {
        final random = Random(seed + run);
        final deleted = <String>{};
        PullPage? saved;
        for (var step = 0; step < 30; step++) {
          final live = [
            for (final r in await store.watch('material_line').first)
              if (!r.deleted) r.id,
          ];
          switch (random.nextInt(8)) {
            case 0:
            case 1:
              await store.create('material_line', {
                'reportId': 'r$run',
                'description': randomText(random),
                'quantity': '${1 + random.nextInt(9)}',
                'unitCode': 'C62',
              });
            case 2:
              if (live.isNotEmpty) {
                await store.change('material_line', live[random.nextInt(live.length)], {
                  'quantity': '${1 + random.nextInt(99)}',
                });
              }
            case 3:
              if (live.isNotEmpty) {
                final id = live[random.nextInt(live.length)];
                await store.delete('material_line', id);
                deleted.add(id);
              }
            case 4:
              server.dropNextAnswer = true;
              await engine.sync();
            case 5:
              server.failBeforeNextPush = random.nextBool();
              server.failNextPull = !server.failBeforeNextPush;
              await engine.sync();
            case 6:
              // A pull answer taken now arrives later, after newer ones (replayed, out of order).
              if (saved == null || random.nextBool()) {
                server.failNextPull = false;
                saved = await server.pull('t', '0');
              } else {
                await store.applyPull(saved);
              }
            case 7:
              await engine.sync();
          }
        }
        server
          ..dropNextAnswer = false
          ..failBeforeNextPush = false
          ..failNextPull = false;
        expect(await engine.sync(), SyncOutcome.done, reason: 'seed ${seed + run}');
        expect(await store.pending(), isEmpty, reason: 'seed ${seed + run}');
        expect(
          server.appliedLog.toSet().length,
          server.appliedLog.length,
          reason: 'seed ${seed + run}',
        );
        for (final id in deleted) {
          expect(
            server.data('material_line', id),
            isNull,
            reason: 'deleted $id; seed ${seed + run}',
          );
          expect(
            await store.record('material_line', id),
            isNull,
            reason: 'deleted $id; seed ${seed + run}',
          );
        }
        for (final (entity, id, data) in server.all) {
          final local = await store.record(entity, id);
          expect(local?.data, data, reason: '$entity $id agrees; seed ${seed + run}');
        }
      }
    },
    timeout: const Timeout(Duration(hours: 2)),
  );

  test(
    'MARA read-only (Stage 10): changes stay in the outbox, office changes still arrive',
    () async {
      await store.create('time_entry', {'reportId': 'r1', 'kind': 'WORK'}, id: 't1');
      server.officeWrite('customer', 'c9', {'name': 'Neu aus dem Büro'});
      server.pushProblem = const ApiProblem(403, 'MARA_READ_ONLY');
      expect(await engine.sync(), SyncOutcome.readOnly);
      expect(await store.pendingCount(), 1, reason: 'nothing is lost or dropped');
      expect((await store.record('customer', 'c9'))!.text('name'), 'Neu aus dem Büro');
      expect(server.data('time_entry', 't1'), isNull);

      server.pushProblem = null;
      expect(await engine.sync(), SyncOutcome.done, reason: 'booked again: the outbox goes out');
      expect(server.data('time_entry', 't1'), isNotNull);
      expect(await store.pendingCount(), 0);
    },
  );

  test('the outbox is sent in batches of at most 200', () async {
    server.officeWrite('work_order', 'wo', {'title': 'Viel', 'status': 'PLANNED'});
    await engine.sync();
    for (var i = 0; i < 450; i++) {
      await store.change('work_order', 'wo', {'workNotes': 'n$i'});
    }
    final sizes = <int>[];
    final counting = _CountingTransport(server, sizes);
    expect(
      await SyncEngine(store: store, transport: counting, authorized: _authorized).sync(),
      SyncOutcome.done,
    );
    expect(sizes, [200, 200, 50]);
    expect(server.data('work_order', 'wo')!['workNotes'], 'n449');
  });
}

class _CountingTransport implements SyncTransport {
  _CountingTransport(this.inner, this.sizes);

  final SyncTransport inner;
  final List<int> sizes;

  @override
  Future<List<MutationResultDto>> push(String accessToken, List<MutationDto> mutations) {
    sizes.add(mutations.length);
    return inner.push(accessToken, mutations);
  }

  @override
  Future<PullPage> pull(String accessToken, String cursor) => inner.pull(accessToken, cursor);
}
