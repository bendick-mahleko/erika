import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:mara/app/version.dart';
import 'package:mara/data/api/auth_api.dart';

import 'support/fakes.dart';

void main() {
  test('device sign-in sends the device and reads tokens, user and recovery codes', () async {
    final server = FakeServer()
      ..routes['POST /api/v1/mobile/auth/device-login'] = (_) =>
          FakeServer.json(FakeServer.session(recoveryCodes: ['AAAA-BBBB']));

    final session = await server.api.deviceLogin(
      email: 'anna@example.test',
      password: 'geheim',
      code: '123456',
      deviceName: 'Pixel 7',
      platform: 'ANDROID',
    );

    final sent = server.requests.single;
    expect(sent.headers['X-App-Version'], appVersion);
    expect(sent.headers.containsKey('Authorization'), isFalse);
    final body = jsonDecode(sent.body) as Map<String, dynamic>;
    expect(body['code'], '123456');
    expect(body.containsKey('recoveryCode'), isFalse, reason: 'absent, not null');
    expect(body['device'], {'name': 'Pixel 7', 'platform': 'ANDROID', 'appVersion': appVersion});
    expect(session.user.role, 'TECHNICIAN');
    expect(session.tokens.accessToken, 'access-1');
    expect(session.recoveryCodes, ['AAAA-BBBB']);
  });

  test('error answers become problems with the stable code', () async {
    final server = FakeServer()
      ..routes['POST /api/v1/mobile/auth/device-login'] = (_) =>
          FakeServer.problem(401, 'TOTP_REQUIRED');
    expect(
      () => server.api.deviceLogin(
        email: 'a@b.test',
        password: 'x',
        deviceName: 'P',
        platform: 'ANDROID',
      ),
      throwsA(isA<ApiProblem>().having((p) => p.code, 'code', 'TOTP_REQUIRED')),
    );

    final html = AuthApi(
      MockClient((_) async => http.Response('<html>Bad gateway</html>', 502)),
      'http://s.test',
    );
    expect(
      () => html.refresh('r'),
      throwsA(isA<ApiProblem>().having((p) => p.code, 'code', 'HTTP_502')),
    );

    final offline = AuthApi(
      MockClient((_) async => throw http.ClientException('offline')),
      'http://s.test',
    );
    expect(
      () => offline.refresh('r'),
      throwsA(isA<ApiProblem>().having((p) => p.isNetwork, 'network', isTrue)),
    );
  });

  test('device status sends the bearer token and reports a required update', () async {
    final server = FakeServer()
      ..routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
          FakeServer.json(FakeServer.deviceStatus(appUpdate: 'REQUIRED'));
    final status = await server.api.deviceStatus('access-1');
    expect(server.requests.single.headers['Authorization'], 'Bearer access-1');
    expect(status.updateRequired, isTrue);
  });
}
