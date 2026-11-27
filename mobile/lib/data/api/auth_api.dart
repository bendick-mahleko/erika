import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../app/version.dart';

/// An error answer of the server (RFC 9457 problem details with ERIKA's stable
/// `code`), or a network failure (`status` 0, `code` `NETWORK`).
class ApiProblem implements Exception {
  const ApiProblem(this.status, this.code);

  final int status;
  final String code;

  bool get isNetwork => status == 0;

  /// The device was revoked or its user left the firm: delete local data (ADR 0037).
  bool get deviceRevoked => code == 'DEVICE_REVOKED';

  /// MARA (or ERIKA) is read-only for the firm: changes stay on the phone (MARA Stage 10).
  bool get readOnly =>
      status == 403 &&
      const {'MARA_READ_ONLY', 'MARA_NOT_BOOKED', 'SUBSCRIPTION_REQUIRED'}.contains(code);

  @override
  String toString() => 'ApiProblem($status $code)';
}

/// Who a device belongs to (`DeviceUser` in api/mobile.yaml).
class DeviceUser {
  const DeviceUser({
    required this.userId,
    required this.displayName,
    required this.role,
    required this.workspaceName,
  });

  factory DeviceUser.fromJson(Map<String, dynamic> json) => DeviceUser(
    userId: json['userId'] as String,
    displayName: json['displayName'] as String,
    role: json['role'] as String,
    workspaceName: json['workspaceName'] as String,
  );

  final String userId;
  final String displayName;

  /// OWNER, MEMBER or TECHNICIAN.
  final String role;
  final String workspaceName;

  Map<String, dynamic> toJson() => {
    'userId': userId,
    'displayName': displayName,
    'role': role,
    'workspaceName': workspaceName,
  };
}

/// `DeviceTokens`: a 15-minute access token and a refresh token that works once.
class DeviceTokens {
  const DeviceTokens({
    required this.accessToken,
    required this.accessTokenExpiresAt,
    required this.refreshToken,
    required this.refreshTokenExpiresAt,
  });

  factory DeviceTokens.fromJson(Map<String, dynamic> json) => DeviceTokens(
    accessToken: json['accessToken'] as String,
    accessTokenExpiresAt: DateTime.parse(json['accessTokenExpiresAt'] as String),
    refreshToken: json['refreshToken'] as String,
    refreshTokenExpiresAt: DateTime.parse(json['refreshTokenExpiresAt'] as String),
  );

  final String accessToken;
  final DateTime accessTokenExpiresAt;
  final String refreshToken;
  final DateTime refreshTokenExpiresAt;

  Map<String, dynamic> toJson() => {
    'accessToken': accessToken,
    'accessTokenExpiresAt': accessTokenExpiresAt.toUtc().toIso8601String(),
    'refreshToken': refreshToken,
    'refreshTokenExpiresAt': refreshTokenExpiresAt.toUtc().toIso8601String(),
  };
}

/// `DeviceSession`: the answer of a successful device sign-in.
class DeviceSession {
  const DeviceSession({
    required this.deviceId,
    required this.tokens,
    required this.user,
    this.recoveryCodes,
  });

  factory DeviceSession.fromJson(Map<String, dynamic> json) => DeviceSession(
    deviceId: json['deviceId'] as String,
    tokens: DeviceTokens.fromJson(json['tokens'] as Map<String, dynamic>),
    user: DeviceUser.fromJson(json['user'] as Map<String, dynamic>),
    recoveryCodes: (json['recoveryCodes'] as List<dynamic>?)?.cast<String>(),
  );

  final String deviceId;
  final DeviceTokens tokens;
  final DeviceUser user;

  /// Only when this sign-in confirmed the 2FA set-up: shown once.
  final List<String>? recoveryCodes;
}

/// `MobileTotpEnrolment`: the new authenticator secret.
class TotpEnrolment {
  const TotpEnrolment({required this.secret, required this.otpauthUri});

  final String secret;
  final String otpauthUri;
}

/// `DeviceStatus`: whether the app may continue.
class DeviceStatus {
  const DeviceStatus({required this.deviceId, required this.user, required this.updateRequired});

  final String deviceId;
  final DeviceUser user;

  /// The server no longer accepts this app version or protocol.
  final bool updateRequired;
}

/// The sign-in endpoints of api/mobile.yaml (tag `mobile-auth`) and the device status.
class AuthApi {
  AuthApi(this._client, this._baseUrl);

  final http.Client _client;
  final String _baseUrl;

  static const _timeout = Duration(seconds: 20);

  Future<TotpEnrolment> startEnrolment(String email, String password) async {
    final json = await _post('/api/v1/mobile/auth/totp-enrolment', {
      'email': email,
      'password': password,
    });
    return TotpEnrolment(
      secret: json['secret'] as String,
      otpauthUri: json['otpauthUri'] as String,
    );
  }

  /// Password and a TOTP or recovery code; registers this phone.
  Future<DeviceSession> deviceLogin({
    required String email,
    required String password,
    String? code,
    String? recoveryCode,
    required String deviceName,
    required String platform,
  }) async => DeviceSession.fromJson(
    await _post('/api/v1/mobile/auth/device-login', {
      'email': email,
      'password': password,
      'code': ?code,
      'recoveryCode': ?recoveryCode,
      'device': {'name': deviceName, 'platform': platform, 'appVersion': appVersion},
    }),
  );

  /// Rotates the refresh token. Never run two at once: a reused token revokes the device.
  Future<DeviceTokens> refresh(String refreshToken) async => DeviceTokens.fromJson(
    await _post('/api/v1/mobile/auth/refresh', {'refreshToken': refreshToken}),
  );

  Future<void> logout(String accessToken) async {
    await _send('POST', '/api/v1/mobile/auth/logout', accessToken: accessToken);
  }

  Future<DeviceStatus> deviceStatus(String accessToken) async {
    final json = await _send('GET', '/api/v1/mobile/devices/me/status', accessToken: accessToken);
    final protocol = json['protocol'] as Map<String, dynamic>;
    final supported =
        syncProtocolVersion >= (protocol['minimum'] as int) &&
        syncProtocolVersion <= (protocol['current'] as int);
    return DeviceStatus(
      deviceId: json['deviceId'] as String,
      user: DeviceUser.fromJson(json['user'] as Map<String, dynamic>),
      updateRequired: json['appUpdate'] == 'REQUIRED' || !supported,
    );
  }

  Future<Map<String, dynamic>> _post(String path, Map<String, dynamic> body) =>
      _send('POST', path, body: body);

  Future<Map<String, dynamic>> _send(
    String method,
    String path, {
    Map<String, dynamic>? body,
    String? accessToken,
  }) async {
    final request = http.Request(method, Uri.parse('$_baseUrl$path'))
      ..headers.addAll({
        'Accept': 'application/json',
        'X-App-Version': appVersion,
        'Authorization': ?(accessToken == null ? null : 'Bearer $accessToken'),
      });
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(body);
    }
    final http.Response response;
    try {
      response = await http.Response.fromStream(await _client.send(request).timeout(_timeout));
    } on Object {
      throw const ApiProblem(0, 'NETWORK');
    }
    final text = utf8.decode(response.bodyBytes);
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (text.isEmpty) return const {};
      return jsonDecode(text) as Map<String, dynamic>;
    }
    var code = 'HTTP_${response.statusCode}';
    try {
      final problem = jsonDecode(text);
      if (problem is Map<String, dynamic> && problem['code'] is String) {
        code = problem['code'] as String;
      }
    } on FormatException {
      // Not a problem document (e.g. a proxy's error page): keep the HTTP status.
    }
    throw ApiProblem(response.statusCode, code);
  }
}
