import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/session/key_vault.dart';
import 'package:mara/data/session/session.dart';

import 'support/fakes.dart';

/// The session's rules (ADR 0037): PIN, lock, token renewal and the lost-device wipe.
void main() {
  late TestDevice device;
  late ProviderContainer container;

  SessionController controller() => container.read(sessionProvider.notifier);
  Future<SessionState> state() => container.read(sessionProvider.future);

  setUp(() {
    device = TestDevice();
    container = ProviderContainer(overrides: device.overrides);
  });
  tearDown(() {
    container.dispose();
    device.files.dir.deleteSync(recursive: true);
  });

  Future<Unlocked> signInAndChoosePin({bool biometrics = false}) async {
    expect(await state(), isA<SignedOut>());
    await controller().signedIn(DeviceSession.fromJson(FakeServer.session()));
    expect(await state(), isA<PinSetup>());
    await controller().choosePin('482913', biometrics: biometrics);
    return await state() as Unlocked;
  }

  test(
    'first sign-in: a new database key, released by the PIN, opens the encrypted database',
    () async {
      final unlocked = await signInAndChoosePin();
      expect(unlocked.user.displayName, 'Anna Geselle');
      expect(device.store.values.values.any((v) => v.contains(unlocked.databaseKey)), isFalse);

      final file = await device.files.databaseFile();
      final db = AppDatabase.openEncrypted(file, unlocked.databaseKey);
      await db.putSetting('k', 'v');
      await db.close();

      controller().lock();
      expect(await state(), isA<Locked>());
      expect((await controller().unlockWithPin('111111')).ok, isFalse);
      final attempt = await controller().unlockWithPin('482913');
      expect(attempt.ok, isTrue);
      final again = await state() as Unlocked;
      expect(again.databaseKey, unlocked.databaseKey);
    },
  );

  test('after a restart the app starts locked', () async {
    await signInAndChoosePin(biometrics: true);
    final restarted = ProviderContainer(overrides: device.overrides);
    addTearDown(restarted.dispose);
    final locked = await restarted.read(sessionProvider.future);
    expect(locked, isA<Locked>());
    expect((locked as Locked).biometrics, isTrue);

    device.biometrics.recognised = false;
    expect(await restarted.read(sessionProvider.notifier).unlockWithBiometrics('x'), isFalse);
    device.biometrics.recognised = true;
    expect(await restarted.read(sessionProvider.notifier).unlockWithBiometrics('x'), isTrue);
    expect(await restarted.read(sessionProvider.future), isA<Unlocked>());
  });

  test('too many wrong PINs delete the local data', () async {
    await signInAndChoosePin();
    controller().lock();
    PinAttempt last = const PinAttempt(ok: false);
    for (var i = 0; i < KeyVault.maxPinAttempts; i++) {
      last = await controller().unlockWithPin('000001');
    }
    expect(last.remaining, 0);
    expect(
      await state(),
      isA<SignedOut>().having((s) => s.reason, 'reason', WipeReason.pinAttempts),
    );
    expect(device.store.values, isEmpty);
  });

  test('a revoked device deletes its database, files and keys', () async {
    final unlocked = await signInAndChoosePin();
    final file = await device.files.databaseFile();
    final db = AppDatabase.openEncrypted(file, unlocked.databaseKey);
    await db.putSetting('k', 'v');
    await db.close();
    File('${device.files.dir.path}/photo.jpg').writeAsBytesSync([1, 2, 3]);

    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.problem(401, 'DEVICE_REVOKED');
    await controller().checkDevice();

    expect(await state(), isA<SignedOut>().having((s) => s.reason, 'reason', WipeReason.revoked));
    expect(device.files.dir.listSync(), isEmpty, reason: 'database and files gone');
    expect(device.store.values, isEmpty, reason: 'tokens and keys gone');
  });

  test(
    'an expired access token is renewed once, also for concurrent calls, and the request repeated',
    () async {
      await signInAndChoosePin();
      var refreshes = 0;
      device.server.routes['POST /api/v1/mobile/auth/refresh'] = (request) {
        refreshes++;
        expect(jsonDecode(request.body), {'refreshToken': 'refresh-1'});
        return FakeServer.json(FakeServer.tokens('access-2', 'refresh-2'));
      };
      device.server.routes['GET /api/v1/mobile/devices/me/status'] = (request) =>
          request.headers['Authorization'] == 'Bearer access-2'
          ? FakeServer.json(FakeServer.deviceStatus())
          : FakeServer.problem(401, 'TOKEN_EXPIRED');

      final api = container.read(authApiProvider);
      final results = await Future.wait([
        controller().authorized(api.deviceStatus),
        controller().authorized(api.deviceStatus),
      ]);
      expect(results.map((s) => s.user.displayName), everyElement('Anna Geselle'));
      expect(refreshes, 1, reason: 'a second refresh with the same token would revoke the device');
      expect(await state(), isA<Unlocked>());
    },
  );

  test('a token about to expire is renewed before the request', () async {
    await signInAndChoosePin();
    device.now = DateTime.now().add(const Duration(minutes: 15));
    device.server.routes['POST /api/v1/mobile/auth/refresh'] = (_) =>
        FakeServer.json(FakeServer.tokens('access-2', 'refresh-2'));
    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.json(FakeServer.deviceStatus());
    await controller().checkDevice();
    expect(device.server.calls('POST /api/v1/mobile/auth/refresh'), hasLength(1));
    expect(device.server.requests.last.headers['Authorization'], 'Bearer access-2');
  });

  test('an expired refresh token asks for a new sign-in and keeps the data', () async {
    final unlocked = await signInAndChoosePin();
    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.problem(401, 'TOKEN_EXPIRED');
    device.server.routes['POST /api/v1/mobile/auth/refresh'] = (_) =>
        FakeServer.problem(401, 'REFRESH_TOKEN_INVALID');
    await controller().checkDevice();
    final reauth = await state() as ReauthRequired;
    expect(reauth.databaseKey, unlocked.databaseKey);
    expect(device.files.wipes, 1, reason: 'only the wipe at the first sign-in');

    await controller().signedIn(
      DeviceSession.fromJson(FakeServer.session(access: 'access-9', refresh: 'refresh-9')),
    );
    final back = await state() as Unlocked;
    expect(back.databaseKey, unlocked.databaseKey, reason: 'same user: same database');
    expect(device.files.wipes, 1);
  });

  test('offline, nothing changes', () async {
    await signInAndChoosePin();
    device.server.routes.clear();
    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        throw const SocketException('offline');
    await controller().checkDevice();
    expect(await state(), isA<Unlocked>());
  });

  test('an outdated app is stopped until the update', () async {
    await signInAndChoosePin();
    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.json(FakeServer.deviceStatus(appUpdate: 'REQUIRED'));
    await controller().checkDevice();
    expect(await state(), isA<UpdateRequired>());
  });

  test('sign-out revokes the device on the server and deletes local data', () async {
    await signInAndChoosePin();
    device.server.routes['POST /api/v1/mobile/auth/logout'] = (_) => FakeServer.json({}, 204);
    await controller().signOut();
    expect(device.server.requests.last.headers['Authorization'], 'Bearer access-1');
    expect(await state(), isA<SignedOut>().having((s) => s.reason, 'reason', WipeReason.signedOut));
    expect(device.store.values, isEmpty);
  });
}
