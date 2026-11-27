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
  bool deleted = false;
  int deletedVersion = 0;
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

  /// No connection at all (flight mode) until set back to false.
  var offline = false;

  /// Answers every push with this problem (e.g. 403 MARA_READ_ONLY), applying nothing.
  ApiProblem? pushProblem;

  static const _lww = FieldPolicy.lastWriterWins;

  static const policies = {
    'work_order': {
      'title': FieldPolicy.serverWins,
      'customerId': FieldPolicy.serverWins,
      'plannedFrom': FieldPolicy.serverWins,
      'status': FieldPolicy.deviceWins,
      'workNotes': FieldPolicy.deviceWins,
    },
    'customer': {'name': _lww, 'phone': _lww},
    'report': {
      'workOrderId': FieldPolicy.serverWins,
      'version': FieldPolicy.serverWins,
      'workDescription': _lww,
      'state': _lww,
    },
    'report_signature': {
      'reportId': FieldPolicy.serverWins,
      'kind': FieldPolicy.serverWins,
      'contentHash': FieldPolicy.serverWins,
      'signerName': FieldPolicy.serverWins,
      'signedAt': FieldPolicy.serverWins,
      'comment': FieldPolicy.serverWins,
      'notSignedReason': FieldPolicy.serverWins,
      'strokes': FieldPolicy.serverWins,
      'signaturePng': FieldPolicy.serverWins,
      'verificationCode': FieldPolicy.serverWins,
      'downloadKey': FieldPolicy.serverWins,
      'customerEmail': FieldPolicy.serverWins,
    },
    'time_entry': {
      'reportId': _lww,
      'technicianId': _lww,
      'kind': _lww,
      'startedAt': _lww,
      'endedAt': _lww,
      'note': _lww,
    },
    'material_line': {
      'reportId': _lww,
      'articleId': _lww,
      'description': _lww,
      'quantity': _lww,
      'unitCode': _lww,
    },
    'aufmass_position': {
      'reportId': _lww,
      'label': _lww,
      'kind': _lww,
      'length': _lww,
      'width': _lww,
      'height': _lww,
      'count': _lww,
      'expression': _lww,
      'deductions': _lww,
      'unitCode': _lww,
    },
    'checklist_answer': {'reportId': _lww, 'itemKey': _lww, 'value': _lww},
    'photo': {
      'reportId': _lww,
      'caption': _lww,
      'category': _lww,
      'checklistItemKey': _lww,
      'takenAt': _lww,
      'sizeBytes': _lww,
      'sha256': _lww,
    },
  };

  /// Child records the phone may delete.
  static const deletable = {'time_entry', 'material_line', 'aufmass_position', 'photo'};

  /// The hash the server computes for a report from its own data (Stage 7); a signature with
  /// another hash is refused, as on the real server. Null: not checked.
  String? Function(String reportId)? reportHash;

  /// Live (not deleted) data of a record.
  Map<String, dynamic>? data(String entity, String id) {
    final r = _records['$entity/$id'];
    return r == null || r.deleted ? null : r.data;
  }

  Iterable<(String, String, Map<String, dynamic>)> get all => _records.entries
      .where((e) => !e.value.deleted)
      .map((e) => (e.value.entity, e.key.split('/')[1], e.value.data));

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
    if (offline) throw const ApiProblem(0, 'NETWORK');
    if (pushProblem != null) throw pushProblem!;
    if (failBeforeNextPush) {
      failBeforeNextPush = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    // Versions earlier mutations of this push answered count as seen (ADR 0038).
    final seen = <String, Map<String, int>>{};
    final results = <MutationResultDto>[];
    for (final m in mutations) {
      final key = '${m.entity}/${m.entityId}';
      final result = _applied[m.mutationId] ??= _apply(m, seen[key] ?? const {});
      final record = result.record;
      if (record != null) {
        final known = seen.putIfAbsent('${record.entity}/${record.id}', () => {});
        record.fieldVersions.forEach((f, v) => known[f] = max(known[f] ?? 0, v));
      }
      results.add(result);
    }
    if (dropNextAnswer) {
      dropNextAnswer = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    return results;
  }

  MutationResultDto _apply(MutationDto m, Map<String, int> seen) {
    final key = '${m.entity}/${m.entityId}';
    final policy = policies[m.entity];
    if (policy == null) {
      return MutationResultDto(
        mutationId: m.mutationId,
        status: 'REJECTED',
        code: 'UNKNOWN_ENTITY',
      );
    }
    final base = {...m.baseVersions};
    seen.forEach((f, v) => base[f] = max(base[f] ?? 0, v));
    appliedLog.add(m.mutationId);
    final r = _records[key];
    switch (m.op) {
      case 'CREATE':
        if (r == null && m.entity == 'report_signature') {
          final reportId = m.values['reportId'] as String;
          final expected = reportHash?.call(reportId);
          if (expected != null && expected != m.values['contentHash']) {
            return MutationResultDto(
              mutationId: m.mutationId,
              status: 'REJECTED',
              code: 'HASH_MISMATCH',
            );
          }
          // The version is locked: SIGNED or NOT_SIGNED (the key is never sent back).
          _write(_records['report/$reportId']!, {'state': m.values['kind']});
          final created = _ServerRecord(m.entity, {});
          _records[key] = created;
          _write(created, {...m.values}..remove('downloadKey'));
          return MutationResultDto(mutationId: m.mutationId, status: 'APPLIED', record: _dto(key));
        }
        if (r == null) {
          final created = _ServerRecord(m.entity, {});
          _records[key] = created;
          // The server's own fields: a photo's file is still to come.
          _write(created, {...m.values, if (m.entity == 'photo') 'state': 'PENDING_FILE'});
          return MutationResultDto(mutationId: m.mutationId, status: 'APPLIED', record: _dto(key));
        }
        // Derived IDs: merged like an edit made without seeing the record.
        final editable = {
          for (final e in m.values.entries)
            if (!const {'reportId', 'itemKey', 'workOrderId'}.contains(e.key)) e.key: e.value,
        };
        return _update(m, key, r, editable, base);
      case 'DELETE':
        if (r == null || r.deleted) {
          return MutationResultDto(mutationId: m.mutationId, status: 'APPLIED');
        }
        if (!deletable.contains(m.entity)) {
          return MutationResultDto(
            mutationId: m.mutationId,
            status: 'REJECTED',
            code: 'DELETE_NOT_ALLOWED',
          );
        }
        if (decideDelete(baseVersions: base, serverVersions: r.fieldVersions) ==
            DeleteDecision.keep) {
          return MutationResultDto(
            mutationId: m.mutationId,
            status: 'CONFLICT',
            record: _dto(key),
            conflicts: const [
              FieldConflictDto(
                field: 'deleted',
                winner: 'SERVER',
                serverValue: false,
                deviceValue: true,
              ),
            ],
          );
        }
        r.deleted = true;
        r.deletedVersion = ++_seq;
        r.version = r.deletedVersion;
        return MutationResultDto(mutationId: m.mutationId, status: 'APPLIED');
      default:
        if (r == null) {
          return MutationResultDto(mutationId: m.mutationId, status: 'REJECTED', code: 'NOT_FOUND');
        }
        return _update(m, key, r, m.values, base);
    }
  }

  MutationResultDto _update(
    MutationDto m,
    String key,
    _ServerRecord r,
    Map<String, dynamic> values,
    Map<String, int> base,
  ) {
    final conflicts = <FieldConflictDto>[];
    if (r.deleted) {
      final maxBase = base.values.fold(0, max);
      if (decideEditOfDeleted(maxBaseVersion: maxBase, deletedVersion: r.deletedVersion) ==
          EditDeletedDecision.reject) {
        return MutationResultDto(mutationId: m.mutationId, status: 'REJECTED', code: 'NOT_FOUND');
      }
      r.deleted = false;
      conflicts.add(
        const FieldConflictDto(
          field: 'deleted',
          winner: 'DEVICE',
          serverValue: true,
          deviceValue: false,
        ),
      );
    }
    final policy = policies[m.entity]!;
    final write = <String, dynamic>{};
    for (final e in values.entries) {
      final decision = decideField(
        policy: policy[e.key] ?? FieldPolicy.serverWins,
        baseVersion: base[e.key] ?? 0,
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
    if (write.isNotEmpty || conflicts.isNotEmpty) _write(r, write);
    return MutationResultDto(
      mutationId: m.mutationId,
      status: conflicts.isEmpty ? 'APPLIED' : 'CONFLICT',
      record: _dto(key),
      conflicts: conflicts,
    );
  }

  @override
  Future<PullPage> pull(String accessToken, String cursor) async {
    if (offline) throw const ApiProblem(0, 'NETWORK');
    if (failNextPull) {
      failNextPull = false;
      throw const ApiProblem(0, 'NETWORK');
    }
    final after = int.tryParse(cursor) ?? 0;
    final changed = _records.keys.where((k) => _records[k]!.version > after).toList()
      ..sort((a, b) => _records[a]!.version.compareTo(_records[b]!.version));
    final page = changed.take(pageSize).toList();
    return PullPage(
      records: [
        for (final k in page)
          if (!_records[k]!.deleted) _dto(k),
      ],
      removals: [
        for (final k in page)
          if (_records[k]!.deleted) (_records[k]!.entity, k.split('/')[1]),
      ],
      nextCursor: page.isEmpty ? '$after' : '${_records[page.last]!.version}',
      hasMore: changed.length > pageSize,
    );
  }
}

String randomText(Random r) => 'v${r.nextInt(100000)}';
