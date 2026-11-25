import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:mara/app/version.dart';
import 'package:mara/data/api/status_api.dart';

void main() {
  const body = {
    'protocol': {'current': 2, 'minimum': 1},
    'minAppVersion': '0.1.0',
    'recommendedAppVersion': '0.2.0',
    'appUpdate': 'RECOMMENDED',
    'serverTime': '2026-11-25T17:00:00+01:00',
  };

  test('reads the status and sends the app version', () async {
    late http.Request sent;
    final api = StatusApi(
      MockClient((request) async {
        sent = request;
        return http.Response(jsonEncode(body), 200, headers: {'content-type': 'application/json'});
      }),
      'http://server.test',
    );

    final status = await api.fetch();

    expect(sent.url.toString(), 'http://server.test/api/v1/mobile/status');
    expect(sent.headers['X-App-Version'], appVersion);
    expect(status.protocolCurrent, 2);
    expect(status.appUpdate, AppUpdate.recommended);
    expect(status.serverTime.toUtc(), DateTime.utc(2026, 11, 25, 16));
    expect(status.supports(1), isTrue);
    expect(status.supports(3), isFalse);
  });

  test('unknown update advice means none', () {
    final status = MobileStatus.fromJson({...body, 'appUpdate': 'SOMETHING_NEW'});
    expect(status.appUpdate, AppUpdate.none);
  });

  test('an error status is an exception', () async {
    final api = StatusApi(MockClient((_) async => http.Response('', 503)), 'http://server.test');
    expect(api.fetch(), throwsA(isA<StatusApiException>()));
  });

  test('a body that is not a status is an exception', () async {
    final api = StatusApi(
      MockClient((_) async => http.Response('{"x":1}', 200)),
      'http://server.test',
    );
    expect(api.fetch(), throwsA(isA<StatusApiException>()));
  });
}
