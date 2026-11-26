import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../app/version.dart';
import '../api/auth_api.dart';

/// `SyncRecord` of api/mobile.yaml: a record's current state. A field missing in [data] is null.
class SyncRecordDto {
  const SyncRecordDto({
    required this.entity,
    required this.id,
    required this.version,
    required this.fieldVersions,
    required this.data,
  });

  factory SyncRecordDto.fromJson(Map<String, dynamic> json) => SyncRecordDto(
    entity: json['entity'] as String,
    id: json['id'] as String,
    version: (json['version'] as num).toInt(),
    fieldVersions: (json['fieldVersions'] as Map<String, dynamic>).map(
      (k, v) => MapEntry(k, (v as num).toInt()),
    ),
    data: Map<String, dynamic>.from(json['data'] as Map),
  );

  final String entity;
  final String id;
  final int version;
  final Map<String, int> fieldVersions;
  final Map<String, dynamic> data;
}

/// One conflict of a pushed mutation.
class FieldConflictDto {
  const FieldConflictDto({
    required this.field,
    required this.winner,
    this.serverValue,
    this.deviceValue,
  });

  final String field;

  /// SERVER or DEVICE.
  final String winner;
  final Object? serverValue;
  final Object? deviceValue;
}

/// `MutationResult`: APPLIED, CONFLICT or REJECTED.
class MutationResultDto {
  const MutationResultDto({
    required this.mutationId,
    required this.status,
    this.record,
    this.conflicts = const [],
    this.code,
  });

  factory MutationResultDto.fromJson(Map<String, dynamic> json) => MutationResultDto(
    mutationId: json['mutationId'] as String,
    status: json['status'] as String,
    record: json['record'] == null
        ? null
        : SyncRecordDto.fromJson(json['record'] as Map<String, dynamic>),
    conflicts: [
      for (final c in (json['conflicts'] as List<dynamic>? ?? const []))
        FieldConflictDto(
          field: (c as Map<String, dynamic>)['field'] as String,
          winner: c['winner'] as String,
          serverValue: c['serverValue'],
          deviceValue: c['deviceValue'],
        ),
    ],
    code: json['code'] as String?,
  );

  final String mutationId;
  final String status;
  final SyncRecordDto? record;
  final List<FieldConflictDto> conflicts;
  final String? code;
}

/// A page of `GET /mobile/sync/pull`.
class PullPage {
  const PullPage({
    required this.records,
    required this.removals,
    required this.nextCursor,
    required this.hasMore,
  });

  final List<SyncRecordDto> records;

  /// (entity, id) of records that left the user's scope.
  final List<(String, String)> removals;
  final String nextCursor;
  final bool hasMore;
}

/// A pending local change, as sent to the server.
class MutationDto {
  const MutationDto({
    required this.mutationId,
    required this.entity,
    required this.entityId,
    required this.op,
    required this.values,
    required this.baseVersions,
  });

  final String mutationId;
  final String entity;
  final String entityId;
  final String op;
  final Map<String, dynamic> values;
  final Map<String, int> baseVersions;

  Map<String, dynamic> toJson() => {
    'mutationId': mutationId,
    'entity': entity,
    'entityId': entityId,
    'op': op,
    'values': values,
    if (op == 'UPDATE') 'baseVersions': baseVersions,
  };
}

/// Transport for the sync protocol (api/mobile.yaml, tag `mobile-sync`).
abstract interface class SyncTransport {
  Future<List<MutationResultDto>> push(String accessToken, List<MutationDto> mutations);

  Future<PullPage> pull(String accessToken, String cursor);
}

class SyncApi implements SyncTransport {
  SyncApi(this._client, this._baseUrl);

  final http.Client _client;
  final String _baseUrl;

  static const _timeout = Duration(seconds: 30);

  @override
  Future<List<MutationResultDto>> push(String accessToken, List<MutationDto> mutations) async {
    final json = await _send(
      'POST',
      Uri.parse('$_baseUrl/api/v1/mobile/sync/push'),
      accessToken,
      jsonEncode({
        'protocolVersion': syncProtocolVersion,
        'mutations': [for (final m in mutations) m.toJson()],
      }),
    );
    return [
      for (final r in json['results'] as List<dynamic>)
        MutationResultDto.fromJson(r as Map<String, dynamic>),
    ];
  }

  @override
  Future<PullPage> pull(String accessToken, String cursor) async {
    final uri = Uri.parse('$_baseUrl/api/v1/mobile/sync/pull').replace(
      queryParameters: {
        'protocolVersion': '$syncProtocolVersion',
        if (cursor.isNotEmpty) 'cursor': cursor,
      },
    );
    final json = await _send('GET', uri, accessToken, null);
    return PullPage(
      records: [
        for (final r in json['records'] as List<dynamic>)
          SyncRecordDto.fromJson(r as Map<String, dynamic>),
      ],
      removals: [
        for (final r in json['removals'] as List<dynamic>)
          ((r as Map<String, dynamic>)['entity'] as String, r['id'] as String),
      ],
      nextCursor: json['nextCursor'] as String,
      hasMore: json['hasMore'] as bool,
    );
  }

  Future<Map<String, dynamic>> _send(String method, Uri uri, String token, String? body) async {
    final request = http.Request(method, uri)
      ..headers.addAll({
        'Accept': 'application/json',
        'X-App-Version': appVersion,
        'Authorization': 'Bearer $token',
        if (body != null) 'Content-Type': 'application/json',
      });
    if (body != null) request.body = body;
    final http.Response response;
    try {
      response = await http.Response.fromStream(await _client.send(request).timeout(_timeout));
    } on Object {
      throw const ApiProblem(0, 'NETWORK');
    }
    final text = utf8.decode(response.bodyBytes);
    if (response.statusCode == 200) return jsonDecode(text) as Map<String, dynamic>;
    var code = 'HTTP_${response.statusCode}';
    try {
      final problem = jsonDecode(text);
      if (problem is Map<String, dynamic> && problem['code'] is String) {
        code = problem['code'] as String;
      }
    } on FormatException {
      // Not a problem document.
    }
    throw ApiProblem(response.statusCode, code);
  }
}
