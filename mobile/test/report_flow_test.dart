import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/app/root.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_providers.dart';
import 'package:mara/domain/derived_id.dart';
import 'package:mara/features/reports/report_screen.dart';
import 'package:mara/l10n/gen/app_localizations.dart';

import 'support/fakes.dart';

/// MARA Stage 5: a whole report recorded on site (M-MO-03, M-MO-04, M-MO-06 to M-MO-08), in
/// flight mode, then synced with nothing lost.
void main() {
  late TestDevice device;
  final me = FakeServer.user['userId']!;
  const wo = '0193a5f0-0000-7000-8000-00000000a001';

  setUp(() => device = TestDevice());
  tearDown(() => device.files.dir.deleteSync(recursive: true));

  Widget app() => ProviderScope(
    overrides: device.overrides,
    retry: (_, _) => null,
    child: MaterialApp(
      locale: const Locale('de'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const MaraRoot(),
    ),
  );

  /// The office's data: a work order with a checklist, an article, the people.
  void officeData() {
    final today = DateTime.now();
    device.syncServer
      ..officeWrite('customer', 'c1', {'name': 'Familie Brandt', 'city': 'Leipzig'})
      ..officeWrite('staff_member', me, {'displayName': 'Anna Geselle', 'role': 'TECHNICIAN'})
      ..officeWrite('article', 'a1', {
        'articleNumber': 'D-15',
        'name': 'Dichtung 1/2 Zoll',
        'unitCode': 'C62',
        'archived': false,
      })
      ..officeWrite('checklist_template', 't1', {
        'name': 'Wartung',
        'items': [
          {'key': 'wasser', 'label': 'Wasser abgestellt', 'kind': 'YES_NO', 'required': true},
          {'key': 'druck', 'label': 'Druck (bar)', 'kind': 'VALUE', 'required': false},
          {'key': 'foto', 'label': 'Foto Typenschild', 'kind': 'PHOTO', 'required': true},
        ],
        'archived': false,
      })
      ..officeWrite('work_order', wo, {
        'title': 'Heizung warten',
        'status': 'PLANNED',
        'customerId': 'c1',
        'assigneeIds': [me],
        'checklistTemplateId': 't1',
        'plannedFrom': DateTime(today.year, today.month, today.day, 9).toUtc().toIso8601String(),
      });
  }

  Future<void> signIn(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    officeData();
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    device.server.routes['POST /api/v1/mobile/auth/device-login'] = (_) =>
        FakeServer.json(FakeServer.session());
    await tester.enterText(find.byKey(const Key('email')), 'anna@example.test');
    await tester.enterText(find.byKey(const Key('password')), 'ein langer Satz');
    await tester.tap(find.text('Weiter'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('pin')), '482913');
    await tester.enterText(find.byKey(const Key('pin-repeat')), '482913');
    await tester.tap(find.text('PIN speichern'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Heizung warten'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open-report')));
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  testWidgets('a whole report in flight mode, then synced: nothing is lost', (tester) async {
    await signIn(tester);
    device.syncServer.offline = true; // flight mode from here on
    final reportId = reportIdFor(wo);

    // Times: a timer for the work (M-MO-03).
    await tap(tester, find.byKey(const Key('start-work-timer')));
    expect(find.textContaining('läuft'), findsOneWidget);

    // Completing is refused while the timer runs and the mandatory item is open (M-MO-07).
    await tap(tester, find.byKey(const Key('complete-report')));
    expect(find.byKey(const Key('cannot-complete')), findsOneWidget);
    expect(find.textContaining('Ein Timer läuft noch'), findsOneWidget);
    expect(find.textContaining('Wasser abgestellt'), findsWidgets);
    expect(
      find.textContaining('Pflichtpunkt offen: Foto'),
      findsNothing,
      reason: 'photos follow in Stage 6',
    );
    await tap(tester, find.text('OK'));

    device.now = device.now.add(const Duration(minutes: 10));
    await tap(tester, find.byKey(const Key('stop-timer')));
    expect(find.textContaining('0:10 h'), findsOneWidget, reason: 'raw time, not rounded');

    // Material from the article list (M-MO-04).
    await tap(tester, find.byKey(const Key('add-material')));
    await tap(tester, find.byKey(const Key('article-a1')));
    await tester.enterText(find.byKey(const Key('quantity')), '2');
    await tap(tester, find.byKey(const Key('add-quantity')));
    expect(find.text('2 Stück'), findsOneWidget);

    // Aufmaß: a wall minus a window (M-MO-06), calculated on the phone like on the server.
    await tap(tester, find.byKey(const Key('add-aufmass')));
    await tester.enterText(find.byKey(const Key('aufmass-label')), 'Wand Bad');
    await tester.enterText(find.byKey(const Key('aufmass-length')), '5,40');
    await tester.enterText(find.byKey(const Key('aufmass-width')), '2,75');
    await tap(tester, find.byKey(const Key('add-deduction')));
    await tester.enterText(find.byKey(const Key('deduction-0-length')), '1,20');
    await tester.enterText(find.byKey(const Key('deduction-0-width')), '1,35');
    await tester.pumpAndSettle();
    expect(find.text('13,230 m²'), findsOneWidget);
    await tap(tester, find.byKey(const Key('save-aufmass')));
    expect(find.text('13,230 m²'), findsOneWidget, reason: 'listed in the report');

    // Checklist and work description (M-MO-07, M-MO-08).
    await tap(
      tester,
      find.descendant(of: find.byKey(const Key('check-wasser-yesno')), matching: find.text('Ja')),
    );
    await tester.enterText(
      find.descendant(
        of: find.byKey(const Key('check-druck-text')),
        matching: find.byType(TextField),
      ),
      '1,8',
    );
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('work-description')),
      'Dichtung getauscht, Anlage entlüftet.',
    );
    await tap(tester, find.byKey(const Key('save-description')));

    await tap(tester, find.byKey(const Key('complete-report')));
    expect(find.byKey(const Key('report-completed')), findsOneWidget);
    expect(device.syncServer.data('report', reportId), isNull, reason: 'still offline');

    // Back online: everything arrives.
    device.syncServer.offline = false;
    final container = ProviderScope.containerOf(tester.element(find.byType(ReportScreen)));
    final outcome = await container.read(syncCoordinatorProvider.notifier).syncNow();
    await tester.pumpAndSettle();
    final server = device.syncServer;
    expect(outcome, SyncOutcome.done);
    expect(server.data('report', reportId)!['state'], 'COMPLETED');
    expect(
      server.data('report', reportId)!['workDescription'],
      'Dichtung getauscht, Anlage entlüftet.',
    );
    expect(server.data('work_order', wo)!['status'], 'DONE');
    final times = server.all.where((r) => r.$1 == 'time_entry').toList();
    expect(times, hasLength(1));
    expect(times.single.$3['endedAt'], isNotNull);
    expect(times.single.$3['technicianId'], me);
    final materials = server.all.where((r) => r.$1 == 'material_line').toList();
    expect(materials.single.$3, containsPair('quantity', '2'));
    expect(materials.single.$3, containsPair('articleId', 'a1'));
    final position = server.all.singleWhere((r) => r.$1 == 'aufmass_position').$3;
    expect(position['length'], '5.4', reason: 'sent with a point, as the server expects');
    expect((position['deductions'] as List<dynamic>).single, {'length': '1.2', 'width': '1.35'});
    expect(
      server.data('checklist_answer', checklistAnswerIdFor(reportId, 'wasser'))!['value'],
      'YES',
    );
    expect(
      server.data('checklist_answer', checklistAnswerIdFor(reportId, 'druck'))!['value'],
      '1,8',
    );
    expect(await (await container.read(syncStoreProvider.future))!.pending(), isEmpty);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('a material used before takes three taps', (tester) async {
    await signIn(tester);
    await tap(tester, find.byKey(const Key('add-material')));
    await tap(tester, find.byKey(const Key('material-free-text')));
    await tester.enterText(find.byKey(const Key('free-text-description')), 'Silikon weiß');
    await tap(tester, find.byKey(const Key('add-free-text')));
    expect(find.text('Silikon weiß'), findsOneWidget);

    var taps = 0;
    Future<void> counted(Finder f) async {
      taps++;
      await tap(tester, f);
    }

    await counted(find.byKey(const Key('add-material')));
    expect(find.text('Zuletzt verwendet'), findsOneWidget);
    await counted(find.byKey(const Key('recent-0')));
    await counted(find.byKey(const Key('add-quantity')));
    expect(taps, lessThanOrEqualTo(3));
    expect(find.text('Silikon weiß'), findsNWidgets(2));
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });

  testWidgets('a time entered afterwards and a deleted material line', (tester) async {
    await signIn(tester);
    await tap(tester, find.byKey(const Key('add-time')));
    await tap(tester, find.text('Fahrt'));
    await tap(tester, find.byKey(const Key('save-time')));
    expect(find.textContaining('Fahrt · 1:00 h'), findsOneWidget);

    await tap(tester, find.byKey(const Key('add-material')));
    await tap(tester, find.byKey(const Key('article-a1')));
    await tap(tester, find.byKey(const Key('add-quantity')));
    final line = find.textContaining('Dichtung');
    expect(line, findsOneWidget);
    await tap(tester, line);
    await tap(tester, find.byKey(const Key('delete-material')));
    expect(find.textContaining('Dichtung'), findsNothing);

    final container = ProviderScope.containerOf(tester.element(find.byType(ReportScreen)));
    await container.read(syncCoordinatorProvider.notifier).syncNow();
    await tester.pumpAndSettle();
    expect(device.syncServer.all.where((r) => r.$1 == 'material_line'), isEmpty);
    expect(device.syncServer.all.where((r) => r.$1 == 'time_entry').single.$3['kind'], 'TRAVEL');
    await tester.pump(const Duration(seconds: 3));
  });
}
