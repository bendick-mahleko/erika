import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/session/device_platform.dart';
import 'package:mara/data/session/key_vault.dart';
import 'package:mara/data/session/session.dart';

/// A scriptable ERIKA server for the mobile endpoints (api/mobile.yaml).
class FakeServer {
  final requests = <http.Request>[];

  /// Answers by `METHOD /path`; a missing route answers 404.
  final routes = <String, http.Response Function(http.Request)>{};

  late final client = MockClient((request) async {
    requests.add(request);
    final route = routes['${request.method} ${request.url.path}'];
    return route == null ? problem(404, 'NOT_FOUND') : route(request);
  });

  AuthApi get api => AuthApi(client, 'http://server.test');

  List<String> calls(String route) =>
      requests.where((r) => '${r.method} ${r.url.path}' == route).map((r) => r.body).toList();

  static http.Response json(Object body, [int status = 200]) =>
      http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json'});

  static http.Response problem(int status, String code) => http.Response(
    jsonEncode({'type': 'about:blank', 'title': 'x', 'status': status, 'code': code}),
    status,
    headers: {'content-type': 'application/problem+json'},
  );

  static Map<String, dynamic> tokens(
    String access,
    String refresh, {
    Duration valid = const Duration(minutes: 15),
  }) => {
    'accessToken': access,
    'accessTokenExpiresAt': DateTime.now().toUtc().add(valid).toIso8601String(),
    'refreshToken': refresh,
    'refreshTokenExpiresAt': DateTime.now().toUtc().add(const Duration(days: 60)).toIso8601String(),
  };

  static const user = {
    'userId': '0193a5f0-0000-7000-8000-000000000001',
    'displayName': 'Anna Geselle',
    'role': 'TECHNICIAN',
    'workspaceName': 'Sanitär Krause',
  };

  static Map<String, dynamic> session({
    List<String>? recoveryCodes,
    String access = 'access-1',
    String refresh = 'refresh-1',
  }) => {
    'deviceId': '0193a5f0-0000-7000-8000-0000000000d1',
    'tokens': tokens(access, refresh),
    'user': user,
    'recoveryCodes': ?recoveryCodes,
  };

  static Map<String, dynamic> deviceStatus({String appUpdate = 'NONE'}) => {
    'deviceId': '0193a5f0-0000-7000-8000-0000000000d1',
    'user': user,
    'protocol': {'current': 1, 'minimum': 1},
    'minAppVersion': '0.1.0',
    'appUpdate': appUpdate,
    'serverTime': DateTime.now().toUtc().toIso8601String(),
  };
}

/// Local files in a temporary directory; records the wipes.
class FakeLocalFiles implements LocalFiles {
  FakeLocalFiles() : dir = Directory.systemTemp.createTempSync('mara-files-');

  final Directory dir;
  var wipes = 0;

  @override
  Future<File> databaseFile() async => File('${dir.path}/mara.sqlite');

  @override
  Future<void> deleteAll() async {
    wipes++;
    for (final entry in dir.listSync()) {
      entry.deleteSync(recursive: true);
    }
  }
}

class FakeBiometrics implements BiometricAuth {
  FakeBiometrics({this.available = true, this.recognised = true});

  bool available;
  bool recognised;
  var prompts = 0;

  @override
  Future<bool> isAvailable() async => available;

  @override
  Future<bool> authenticate(String reason) async {
    prompts++;
    return recognised;
  }
}

/// Everything the session needs, with fakes; a few PBKDF2 rounds keep tests fast.
class TestDevice {
  final server = FakeServer();
  final store = MemorySecureStore();
  final files = FakeLocalFiles();
  final biometrics = FakeBiometrics();
  DateTime now = DateTime.now();

  List<Override> get overrides => [
    authApiProvider.overrideWithValue(server.api),
    secureStoreProvider.overrideWithValue(store),
    keyVaultProvider.overrideWithValue(KeyVault(store, iterations: 1000)),
    localFilesProvider.overrideWithValue(files),
    biometricAuthProvider.overrideWithValue(biometrics),
    clockProvider.overrideWithValue(() => now),
  ];
}
