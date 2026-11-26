import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:drift/native.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/session/device_platform.dart';
import 'package:mara/data/session/key_vault.dart';
import 'package:mara/data/session/session.dart';
import 'package:mara/data/media/media_providers.dart';
import 'package:mara/data/media/photo_uploader.dart';
import 'package:mara/data/sync/sync_providers.dart';

import 'fake_sync_server.dart';
import 'fake_tus_server.dart';

/// A small valid JPEG (64 × 48) as the camera's photo.
final testJpeg = Uint8List.fromList(
  base64Decode(
    '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAwAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5qgs89q0ILDPatO0s844rbs7DOOK7Mvwn1qxjQx3L1MKDTM4+WtGDSc/w1uCFICF2b39B2+tTw208p6lR6LxXp1cLgaEvZNOc+qj09Xt+b8j6DCY6T1vYzI9JRQC+1R6nircen26kAsp+gz/KtWLTYoseaQpPbqavQW6n/VwE8dW45+lctWNCD5ZUoRfZ3k/ujZ/efSYTGt9TIjsLcYwrPn0Xp+dX4NPhP/LKT/vkf41rpayKFLmOIew6/nVuC2GQQ8z5/u5GP5VzVPYxnaUIrysl9ybcvw/A+kwuMfc880+zzjitowGCJdgG9uBx096s6XZ5xxWilp5t23Bwvyjj0/8Ar5p4DFToYFOl8c2op9u7+78Wj+bKGLvLUzLHTRjJAAHUmr0ULu22AbU6bscn/CtM2peQQp9xfvY7mrqW2391CPm6Mw7ew96iVeNGLpUnyxjo2t5P+WP6/olr9HhcXfVmVDZRQHaV3P8A3QK0IrWQruYiKP2/x65rRhshBtREDSeh6CrkVmFb94DJLx8vp/hXHKu6ScF7qW6Tsl5NrWT/ALq+5M+kwuLv1My2sl4EMWT6sOKvRW54Bctk5xEOn41pranAEnJ/55px+dXIrYqApwn+yg+b8/8A9VcU8Qqd0tEvkvSyaX/gTb8uh9LhcXexwel2oWMuRwoycVesbTyoDJtyQOPrV+ytStm5A7Y/PitFLPKwxgDk7j6/560UMU4UKTjvGF16yfKvxVz+bMNi7szLa08iAFQN7cLx+tXre08hVRF/eMMjjoPWtOO1BmZm+5EMf4/jmrdvasq7yP3sh44zispV1TaUHZR0T7JaNrzk9I/LsfR4XF3M2Cz2/uoR83RmHb2+tXbe0C/LFtVRwZD/AErShsto8tMBAMuw/lVyK1BCsykIPuJ3J9a4J4jlt0tppsl1Sfbu95PRH0uFxfmZsFoFX5B5cecb8cn6Vet7TYowvlqenGWP+H+ea04rJgecGTr7IKtw2v8AECRnqxHzN9P8/lXLPEOD7W+Vu/XS2l0mv70m9D6XC4vzP//Z',
  ),
);

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
  Future<Directory> photosDirectory() async =>
      Directory('${dir.path}/photos').create(recursive: true);

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
  final syncServer = FakeSyncServer();
  late final tusServer = FakeTusServer(syncServer);
  final photoFiles = MemoryPhotoFiles();

  /// What the camera "takes" next (null: the user cancels).
  Uint8List? nextPhoto = testJpeg;
  var onWifi = true;

  List<Override> get overrides => [
    authApiProvider.overrideWithValue(server.api),
    secureStoreProvider.overrideWithValue(store),
    keyVaultProvider.overrideWithValue(KeyVault(store, iterations: 1000)),
    localFilesProvider.overrideWithValue(files),
    biometricAuthProvider.overrideWithValue(biometrics),
    clockProvider.overrideWithValue(() => now),
    syncTransportProvider.overrideWithValue(syncServer),
    tusTransportProvider.overrideWithValue(tusServer),
    photoFilesProvider.overrideWithValue(photoFiles),
    photoCaptureProvider.overrideWithValue(() async => nextPhoto),
    onWifiProvider.overrideWithValue(() async => onWifi),
    syncIntervalProvider.overrideWithValue(null),
    // In memory and in this isolate, so widget tests' fake time works; SQLCipher itself is
    // covered by the database and sync tests.
    appDatabaseProvider.overrideWith((ref) async {
      final session = ref.watch(sessionProvider).value;
      if (session is! Unlocked && session is! ReauthRequired) return null;
      final db = AppDatabase(
        DatabaseConnection(NativeDatabase.memory(), closeStreamsSynchronously: true),
      );
      ref.onDispose(db.close);
      return db;
    }),
  ];
}
