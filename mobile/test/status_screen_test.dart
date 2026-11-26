import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/api/status_api.dart';
import 'package:mara/data/session/device_platform.dart';
import 'package:mara/data/session/session.dart';
import 'package:mara/features/status/status_providers.dart';
import 'package:mara/features/status/status_screen.dart';
import 'package:mara/l10n/gen/app_localizations.dart';

Widget app(List<Override> overrides, {Locale locale = const Locale('de')}) => ProviderScope(
  // No signed-in session: the screen's server and database parts on their own.
  overrides: [secureStoreProvider.overrideWithValue(MemorySecureStore()), ...overrides],
  // Riverpod retries failed providers on its own; tests check the retry button alone.
  retry: (_, _) => null,
  child: MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: const StatusScreen(),
  ),
);

final database = localDatabaseInfoProvider.overrideWith(
  (ref) async => const LocalDatabaseInfo(
    cipherVersion: '4.6.1 community',
    openTime: Duration(milliseconds: 42),
  ),
);

void main() {
  testWidgets('shows the server status and the encrypted database', (tester) async {
    await tester.pumpWidget(
      app([
        mobileStatusProvider.overrideWith(
          (ref) async => MobileStatus(
            protocolCurrent: 1,
            protocolMinimum: 1,
            minAppVersion: '0.1.0',
            recommendedAppVersion: '0.1.0',
            appUpdate: AppUpdate.required,
            serverTime: DateTime.now(),
          ),
        ),
        database,
      ]),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('server-status')), findsOneWidget);
    expect(find.text('Version 1 wird unterstützt'), findsOneWidget);
    expect(find.text('Update erforderlich'), findsOneWidget);
    expect(find.text('4.6.1 community'), findsOneWidget);
    expect(find.text('42 ms'), findsOneWidget);
  });

  testWidgets('offers a retry when the server is unreachable', (tester) async {
    var calls = 0;
    await tester.pumpWidget(
      app([
        mobileStatusProvider.overrideWith((ref) async {
          calls++;
          throw const StatusApiException('offline');
        }),
        database,
      ]),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('server-error')), findsOneWidget);
    await tester.tap(find.text('Erneut versuchen'));
    await tester.pumpAndSettle();
    expect(calls, 2);
  });

  testWidgets('speaks English when the device does', (tester) async {
    await tester.pumpWidget(
      app([
        mobileStatusProvider.overrideWith((ref) async => throw const StatusApiException('offline')),
        database,
      ], locale: const Locale('en')),
    );
    await tester.pumpAndSettle();
    expect(find.text('Try again'), findsOneWidget);
    expect(find.text('Data on this device'), findsOneWidget);
  });
}
