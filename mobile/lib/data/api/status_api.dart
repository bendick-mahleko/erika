import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../app/version.dart';

/// Update advice from the server (`AppUpdate` in api/mobile.yaml).
enum AppUpdate { none, recommended, required }

/// `MobileStatus` from api/mobile.yaml.
class MobileStatus {
  const MobileStatus({
    required this.protocolCurrent,
    required this.protocolMinimum,
    required this.minAppVersion,
    required this.recommendedAppVersion,
    required this.appUpdate,
    required this.serverTime,
  });

  factory MobileStatus.fromJson(Map<String, dynamic> json) {
    final protocol = json['protocol'] as Map<String, dynamic>;
    return MobileStatus(
      protocolCurrent: protocol['current'] as int,
      protocolMinimum: protocol['minimum'] as int,
      minAppVersion: json['minAppVersion'] as String,
      recommendedAppVersion: json['recommendedAppVersion'] as String,
      appUpdate: switch (json['appUpdate']) {
        'REQUIRED' => AppUpdate.required,
        'RECOMMENDED' => AppUpdate.recommended,
        _ => AppUpdate.none,
      },
      serverTime: DateTime.parse(json['serverTime'] as String),
    );
  }

  final int protocolCurrent;
  final int protocolMinimum;
  final String minAppVersion;
  final String recommendedAppVersion;
  final AppUpdate appUpdate;
  final DateTime serverTime;

  /// Whether the server still accepts this app's sync protocol.
  bool supports(int protocolVersion) =>
      protocolVersion >= protocolMinimum && protocolVersion <= protocolCurrent;
}

/// The server answered with an error or something that isn't a status.
class StatusApiException implements Exception {
  const StatusApiException(this.message);

  final String message;

  @override
  String toString() => 'StatusApiException($message)';
}

/// `GET /api/v1/mobile/status`: works before sign-in (no token).
class StatusApi {
  StatusApi(this._client, this._baseUrl);

  final http.Client _client;
  final String _baseUrl;

  Future<MobileStatus> fetch() async {
    final uri = Uri.parse('$_baseUrl/api/v1/mobile/status');
    final response = await _client
        .get(uri, headers: {'Accept': 'application/json', 'X-App-Version': appVersion})
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) {
      throw StatusApiException('HTTP ${response.statusCode}');
    }
    try {
      return MobileStatus.fromJson(
        jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>,
      );
    } on Object {
      throw const StatusApiException('unexpected response');
    }
  }
}
