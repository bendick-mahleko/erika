import 'dart:convert';
import 'dart:math';

import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/sync/sync_api.dart';
import 'package:mara/domain/rules/conflict_rules.dart';

class _ServerRecord {
  _ServerRecord(this.entity, this.data);

  final String entity;
  Map<String, dynamic> data;
  int version = 0;
  final fieldVersions = <String, int>{};
}

/// An in-memory ERIKA server for the app's sync tests: the protocol's rules (ADR 0038), with
/// switchable faults. Pages are small so paging is exercised.
class FakeSyncServer implements SyncTransport {
  FakeSyncServer({this.pageSize = 5});

  final int pageSize;
  var _seq = 0;
  final _records = <String, _ServerRecord>{}; // "entity/id"
  final _applied = <String, MutationResultDto>{};

  /// Every mutation the server applied, in order (each ID at most once).
  final appliedLog = <String>[];

  /// The next push fails before reaching the server (connection lost).
  var failBeforeNextPush = false;

  /// The next push is applied, but the answer is lost (timeout after commit, app killed).
  var dropNextAnswer = false;

  /// The next pull fails.
  var failNextPull = false;

  static const policies = {
    'work_order': {
      'title': FieldPolicy.serverWins,
      'customerId': FieldPolicy.serverWins,
      'plannedFrom': FieldPolicy.serverWins,
      'status': FieldPolicy.deviceWins,
      'workNotes': FieldPolicy.deviceWins,
    },
    'customer': {'name': FieldPolicy.lastWriterWins, 'phone': FieldPolicy.lastWriterWins},
  };

  Map<String, dynamic>? data(String entity, String id) => _records['$entity/$id']?.data;

  Iterable<(String, String, Map<String, dynamic>)> get all =>
      _records.entries.map((e) => (e.value.entity, e.key.split('/')[1], e.value.data));

  /// The office changes a record (or creates it).
  void officeWrite(String entity, String id, Map<String, dynamic> values) {
    final r = _records.putIfAbsent('$entity/$id', () => _ServerRecord(entity, {}));
    _write(r, values);
  }

  void _write(_ServerRecord r, Map<String, dynamic> values) {
    final seq = ++_seq;
    for (final e in values.entries) {
      if (jsonEncode(r.data[e.key]) != jsonEncode(e.value) || !r.fieldVersions.containsKey(e.key)) {
        r.fieldVersions[e.key] = seq;
      }
    }
    r.data = {...r.data, ...values};
    r.version = seq;
  }

  SyncRecordDto _dto(String key) {
    final r = _records[key]!;
    return SyncRecordDto(
      entity: r.entity,
      id: key.split('/')[1],
      version: r.version,
      fieldVersions: Map.of(r.fieldVersions),
      data: Map.of(r.data),
    );
  }

  @override
  Future<List<MutationResultDto>> push(String accessToken, List<MutationDto> mutations) async {
    if (failBeforeNextPush) {
      failBeforeNextPush = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    final results = [for (final m in mutations) _applied[m.mutationId] ??= _apply(m)];
    if (dropNextAnswer) {
      dropNextAnswer = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    return results;
  }

  MutationResultDto _apply(MutationDto m) {
    final key = '${m.entity}/${m.entityId}';
    final policy = policies[m.entity];
    if (policy == null) {
      return MutationResultDto(
        mutationId: m.mutationId,
        status: 'REJECTED',
        code: 'UNKNOWN_ENTITY',
      );
    }
    appliedLog.add(m.mutationId);
    if (m.op == 'CREATE') {
      if (!_records.containsKey(key)) {
        final r = _ServerRecord(m.entity, {});
        _records[key] = r;
        _write(r, m.values);
      }
      return MutationResultDto(mutationId: m.mutationId, status: 'APPLIED', record: _dto(key));
    }
    final r = _records[key];
    if (r == null) {
      return MutationResultDto(mutationId: m.mutationId, status: 'REJECTED', code: 'NOT_FOUND');
    }
    final write = <String, dynamic>{};
    final conflicts = <FieldConflictDto>[];
    for (final e in m.values.entries) {
      final decision = decideField(
        policy: policy[e.key] ?? FieldPolicy.serverWins,
        baseVersion: m.baseVersions[e.key] ?? 0,
        serverVersion: r.fieldVersions[e.key] ?? 0,
        sameValue: jsonEncode(r.data[e.key]) == jsonEncode(e.value),
      );
      switch (decision) {
        case FieldDecision.apply:
          write[e.key] = e.value;
        case FieldDecision.same:
          break;
        case FieldDecision.keepServer:
          conflicts.add(
            FieldConflictDto(
              field: e.key,
              winner: 'SERVER',
              serverValue: r.data[e.key],
              deviceValue: e.value,
            ),
          );
        case FieldDecision.applyOver:
          write[e.key] = e.value;
          conflicts.add(
            FieldConflictDto(
              field: e.key,
              winner: 'DEVICE',
              serverValue: r.data[e.key],
              deviceValue: e.value,
            ),
          );
      }
    }
    if (write.isNotEmpty) _write(r, write);
    return MutationResultDto(
      mutationId: m.mutationId,
      status: conflicts.isEmpty ? 'APPLIED' : 'CONFLICT',
      record: _dto(key),
      conflicts: conflicts,
    );
  }

  @override
  Future<PullPage> pull(String accessToken, String cursor) async {
    if (failNextPull) {
      failNextPull = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    final after = int.tryParse(cursor) ?? 0;
    final changed = _records.keys.where((k) => _records[k]!.version > after).toList()
      ..sort((a, b) => _records[a]!.version.compareTo(_records[b]!.version));
    final page = changed.take(pageSize).toList();
    return PullPage(
      records: [for (final k in page) _dto(k)],
      removals: const [],
      nextCursor: page.isEmpty ? '$after' : '${_records[page.last]!.version}',
      hasMore: changed.length > pageSize,
    );
  }
}

String randomText(Random r) => 'v${r.nextInt(100000)}';
