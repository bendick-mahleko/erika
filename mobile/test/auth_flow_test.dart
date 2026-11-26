import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/app/root.dart';
import 'package:mara/data/api/status_api.dart';
import 'package:mara/features/auth/pin_screens.dart';
import 'package:mara/features/status/status_providers.dart';
import 'package:mara/features/workorders/work_order_detail_screen.dart';
import 'package:mara/features/workorders/work_order_list_screen.dart';
import 'package:mara/l10n/gen/app_localizations.dart';

import 'support/fakes.dart';

/// The technician's way through the app (ADR 0037): sign-in, 2FA, PIN, lock and lost device.
void main() {
  late TestDevice device;

  setUp(() => device = TestDevice());
  tearDown(() => device.files.dir.deleteSync(recursive: true));

  Widget app() => ProviderScope(
    overrides: [
      ...device.overrides,
      mobileStatusProvider.overrideWith(
        (ref) async => MobileStatus(
          protocolCurrent: 1,
          protocolMinimum: 1,
          minAppVersion: '0.1.0',
          recommendedAppVersion: '0.1.0',
          appUpdate: AppUpdate.none,
          serverTime: DateTime.now(),
        ),
      ),
      localDatabaseInfoProvider.overrideWith(
        (ref) async => const LocalDatabaseInfo(
          cipherVersion: '4.6.1 community',
          openTime: Duration(milliseconds: 5),
        ),
      ),
    ],
    retry: (_, _) => null,
    child: MaterialApp(
      locale: const Locale('de'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const MaraRoot(),
    ),
  );

  Future<void> openInfo(WidgetTester tester) async {
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Info und Gerät'));
    await tester.pumpAndSettle();
  }

  Future<void> enterCredentials(WidgetTester tester) async {
    await tester.enterText(find.byKey(const Key('email')), 'anna@example.test');
    await tester.enterText(find.byKey(const Key('password')), 'ein langer Satz');
    await tester.tap(find.text('Weiter'));
    await tester.pumpAndSettle();
  }

  Future<void> choosePin(WidgetTester tester) async {
    expect(find.text('PIN festlegen'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('pin')), '123456');
    await tester.enterText(find.byKey(const Key('pin-repeat')), '123456');
    await tester.tap(find.text('PIN speichern'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('error')), findsOneWidget, reason: '123456 is too simple');
    await tester.enterText(find.byKey(const Key('pin')), '482913');
    await tester.enterText(find.byKey(const Key('pin-repeat')), '482913');
    await tester.tap(find.text('PIN speichern'));
    await tester.pumpAndSettle();
  }

  Future<void> signedInAndUnlocked(WidgetTester tester) async {
    device.server.routes['POST /api/v1/mobile/auth/device-login'] = (request) =>
        (jsonDecode(request.body) as Map<String, dynamic>).containsKey('code')
        ? FakeServer.json(FakeServer.session())
        : FakeServer.problem(401, 'TOTP_REQUIRED');
    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.json(FakeServer.deviceStatus());
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await enterCredentials(tester);
    await tester.enterText(find.byKey(const Key('code')), '123456');
    await tester.tap(find.widgetWithText(FilledButton, 'Anmelden'));
    await tester.pumpAndSettle();
    await choosePin(tester);
  }

  testWidgets('sign in with password and TOTP, choose a PIN, see who is signed in', (tester) async {
    await signedInAndUnlocked(tester);
    expect(find.byType(WorkOrderListScreen), findsOneWidget, reason: 'the work orders come first');
    await openInfo(tester);
    expect(find.byKey(const Key('account')), findsOneWidget);
    expect(find.text('Anna Geselle'), findsOneWidget);
    expect(find.text('Techniker/in'), findsOneWidget);
    final login =
        jsonDecode(device.server.calls('POST /api/v1/mobile/auth/device-login').last)
            as Map<String, dynamic>;
    expect(login['code'], '123456');
  });

  testWidgets('an account without 2FA sets it up in the app and sees its recovery codes once', (
    tester,
  ) async {
    device.server.routes['POST /api/v1/mobile/auth/device-login'] = (request) =>
        (jsonDecode(request.body) as Map<String, dynamic>).containsKey('code')
        ? FakeServer.json(FakeServer.session(recoveryCodes: ['ABCD-EFGH-IJKL', 'MNOP-QRST-UVWX']))
        : FakeServer.problem(409, 'TOTP_ENROLMENT_REQUIRED');
    device.server.routes['POST /api/v1/mobile/auth/totp-enrolment'] = (_) => FakeServer.json({
      'secret': 'JBSWY3DPEHPK3PXP',
      'otpauthUri': 'otpauth://totp/ERIKA:anna?secret=JBSWY3DPEHPK3PXP',
    });
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await enterCredentials(tester);

    expect(find.text('Zwei-Faktor-Anmeldung einrichten'), findsOneWidget);
    expect(find.text('JBSW Y3DP EHPK 3PXP'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('code')), '654321');
    await tester.tap(find.text('Bestätigen'));
    await tester.pumpAndSettle();

    expect(find.text('Wiederherstellungscodes'), findsOneWidget);
    expect(find.textContaining('ABCD-EFGH-IJKL'), findsOneWidget);
    await tester.tap(find.text('Ich habe die Codes notiert'));
    await tester.pumpAndSettle();
    expect(find.text('PIN festlegen'), findsOneWidget);
  });

  testWidgets('wrong credentials show the reason', (tester) async {
    device.server.routes['POST /api/v1/mobile/auth/device-login'] = (_) =>
        FakeServer.problem(401, 'INVALID_CREDENTIALS');
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await enterCredentials(tester);
    expect(find.text('E-Mail-Adresse oder Passwort ist falsch.'), findsOneWidget);
  });

  testWidgets('the app locks after 5 minutes in the background, not after a short switch', (
    tester,
  ) async {
    await signedInAndUnlocked(tester);
    // The lock screen offers the fingerprint at once; here it isn't recognised.
    device.biometrics.recognised = false;

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    device.now = device.now.add(const Duration(minutes: 2));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.byType(WorkOrderListScreen), findsOneWidget, reason: 'two minutes: still open');

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    device.now = device.now.add(const Duration(minutes: 5));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.byType(LockScreen), findsOneWidget);
    expect(find.text('Hallo Anna Geselle'), findsOneWidget);
    expect(
      find.byType(WorkOrderListScreen),
      findsNothing,
      reason: 'nothing stays open over the lock',
    );
    expect(device.biometrics.prompts, 1, reason: 'biometrics offered right away');

    await tester.enterText(find.byKey(const Key('pin')), '111111');
    await tester.tap(find.text('Entsperren'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Noch 9 Versuche'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('pin')), '482913');
    await tester.tap(find.text('Entsperren'));
    await tester.pumpAndSettle();
    expect(find.byType(WorkOrderListScreen), findsOneWidget);
  });

  testWidgets('lost device: revoked in the web app, the next contact deletes the local data', (
    tester,
  ) async {
    await signedInAndUnlocked(tester);
    expect(device.store.values, isNotEmpty);

    device.server.routes['GET /api/v1/mobile/devices/me/status'] = (_) =>
        FakeServer.problem(401, 'DEVICE_REVOKED');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('sign-in-notice')), findsOneWidget);
    expect(
      find.text('Dieses Telefon wurde abgemeldet. Die Daten auf dem Gerät wurden gelöscht.'),
      findsOneWidget,
    );
    expect(device.store.values, isEmpty);
    expect(device.files.dir.listSync(), isEmpty);
  });

  testWidgets('sign-out asks first, then deletes the local data', (tester) async {
    await signedInAndUnlocked(tester);
    await openInfo(tester);
    device.server.routes['POST /api/v1/mobile/auth/logout'] = (_) => FakeServer.json({}, 204);
    await tester.tap(find.byKey(const Key('sign-out')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Abbrechen'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('account')), findsOneWidget);

    await tester.tap(find.byKey(const Key('sign-out')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirm-sign-out')));
    await tester.pumpAndSettle();
    expect(
      find.text('Sie sind abgemeldet. Die Daten auf dem Gerät wurden gelöscht.'),
      findsOneWidget,
    );
    expect(device.server.calls('POST /api/v1/mobile/auth/logout'), hasLength(1));
  });

  testWidgets('office work orders appear; a status change on site reaches the server', (
    tester,
  ) async {
    final today = DateTime.now();
    final at9 = DateTime(today.year, today.month, today.day, 9).toUtc().toIso8601String();
    device.syncServer
      ..officeWrite('customer', 'c1', {
        'name': 'Familie Keller',
        'street': 'Gartenweg 3',
        'postalCode': '04109',
        'city': 'Leipzig',
        'phone': '0341 123456',
      })
      ..officeWrite('work_order', 'wo1', {
        'title': 'Heizung warten',
        'status': 'PLANNED',
        'customerId': 'c1',
        'plannedFrom': at9,
      });
    final launched = <Uri>[];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          ...device.overrides,
          externalLauncherProvider.overrideWithValue((uri) async {
            launched.add(uri);
            return true;
          }),
        ],
        retry: (_, _) => null,
        child: MaterialApp(
          locale: const Locale('de'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const MaraRoot(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    device.server.routes['POST /api/v1/mobile/auth/device-login'] = (_) =>
        FakeServer.json(FakeServer.session());
    await tester.enterText(find.byKey(const Key('email')), 'anna@example.test');
    await tester.enterText(find.byKey(const Key('password')), 'ein langer Satz');
    await tester.tap(find.text('Weiter'));
    await tester.pumpAndSettle();
    await choosePin(tester);
    await tester.pumpAndSettle();

    expect(find.text('Heute'), findsOneWidget);
    expect(find.text('Heizung warten'), findsOneWidget);
    await tester.tap(find.text('Heizung warten'));
    await tester.pumpAndSettle();
    expect(find.text('Gartenweg 3, 04109 Leipzig'), findsOneWidget);

    await tester.tap(find.byKey(const Key('route')));
    await tester.tap(find.byKey(const Key('call')));
    expect(launched.map((u) => u.scheme), ['geo', 'tel']);
    expect(launched.last.path, '0341123456');

    await tester.tap(find.text('In Arbeit'));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('pending')),
      findsOneWidget,
      reason: 'saved on the phone, not yet sent',
    );
    await tester.pump(const Duration(seconds: 3)); // the sync a moment after the change
    await tester.pumpAndSettle();
    expect(device.syncServer.data('work_order', 'wo1')!['status'], 'IN_PROGRESS');
    expect(find.byKey(const Key('pending')), findsNothing);
  });

  test('simple PINs are refused', () {
    expect(isAcceptablePin('482913'), isTrue);
    for (final pin in ['123456', '654321', '000000', '12345', '12345a']) {
      expect(isAcceptablePin(pin), isFalse, reason: pin);
    }
  });
}
