import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/app/root.dart';
import 'package:mara/data/sync/sync_engine.dart';
import 'package:mara/data/sync/sync_providers.dart';
import 'package:mara/domain/derived_id.dart';
import 'package:mara/domain/rules/report_canonical.dart';
import 'package:mara/features/reports/aufmass_editor_screen.dart';
import 'package:mara/features/reports/report_screen.dart';
import 'package:mara/l10n/gen/app_localizations.dart';

import 'support/fake_sync_server.dart';
import 'support/fakes.dart';

/// The canonical report rebuilt from the server's records, as the real server does
/// (`ReportSnapshots`): the phone's hash must match it.
Map<String, Object?> serverSnapshot(FakeSyncServer s, String reportId) {
  final report = s.data('report', reportId)!;
  final wo = s.data('work_order', report['workOrderId'] as String)!;
  final names = {
    for (final r in s.all)
      if (r.$1 == 'staff_member') r.$2: r.$3['displayName'],
  };
  List<(String, Map<String, dynamic>)> of(String entity) => [
    for (final r in s.all)
      if (r.$1 == entity && r.$3['reportId'] == reportId) (r.$2, r.$3),
  ];
  final answers = {for (final (_, a) in of('checklist_answer')) a['itemKey']: a['value']};
  final items =
      (s.data('checklist_template', wo['checklistTemplateId'] as String)!['items'] as List<dynamic>)
          .cast<Map<String, dynamic>>();
  return {
    'format': 1,
    'reportId': reportId,
    'version': report['version'] ?? 1,
    'workOrder': {'id': report['workOrderId'], 'title': wo['title']},
    'customer': {'name': s.data('customer', wo['customerId'] as String)!['name']},
    'workDescription': report['workDescription'],
    'times': [
      for (final (id, t) in of('time_entry'))
        {
          'id': id,
          'technician': names[t['technicianId']],
          'kind': t['kind'] ?? 'WORK',
          'start': t['startedAt'],
          'end': t['endedAt'],
          'note': t['note'],
        },
    ],
    'materials': [
      for (final (id, m) in of('material_line'))
        {
          'id': id,
          'description': m['description'],
          'quantity': m['quantity'],
          'unit': m['unitCode'],
        },
    ],
    'aufmass': [
      for (final (id, p) in of('aufmass_position'))
        {
          'id': id,
          for (final k in [
            'label',
            'kind',
            'length',
            'width',
            'height',
            'count',
            'expression',
            'deductions',
          ])
            k: p[k],
          'result': aufmassResultOf(p),
          'unit': p['unitCode'],
        },
    ],
    'checklist': [
      for (final i in items)
        if (i['kind'] != 'PHOTO')
          {'key': i['key'], 'label': i['label'], 'value': answers[i['key']]},
    ],
    'photos': [
      for (final (id, p) in of('photo'))
        {
          'id': id,
          'category': p['category'],
          'caption': p['caption'],
          'checklistItemKey': p['checklistItemKey'],
          'sha256': p['sha256'],
        },
    ],
  };
}

/// MARA Stage 7 (M-MO-09 to M-MO-11, M-BR-01, M-BR-03): preview, signature, lock, customer PDF.
void main() {
  late TestDevice device;
  final me = FakeServer.user['userId']!;
  const wo = '0193a5f0-0000-7000-8000-00000000b001';

  setUp(() => device = TestDevice());
  tearDown(() => device.files.dir.deleteSync(recursive: true));

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  ProviderContainer container(WidgetTester tester) =>
      ProviderScope.containerOf(tester.element(find.byType(ReportScreen)));

  /// Signed in, with a recorded and completed report on screen.
  Future<void> completedReport(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    device.syncServer
      ..officeWrite('customer', 'c1', {'name': 'Familie Sommer', 'email': 'sommer@example.test'})
      ..officeWrite('staff_member', me, {'displayName': 'Anna Geselle', 'role': 'TECHNICIAN'})
      ..officeWrite('checklist_template', 't1', {
        'name': 'Wartung',
        'items': [
          {'key': 'wasser', 'label': 'Wasser abgestellt', 'kind': 'YES_NO', 'required': true},
          {'key': 'foto', 'label': 'Foto Typenschild', 'kind': 'PHOTO', 'required': true},
        ],
      })
      ..officeWrite('work_order', wo, {
        'title': 'Therme warten',
        'status': 'IN_PROGRESS',
        'customerId': 'c1',
        'assigneeIds': [me],
        'checklistTemplateId': 't1',
      });
    await tester.pumpWidget(
      ProviderScope(
        overrides: device.overrides,
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
    await tester.enterText(find.byKey(const Key('pin')), '482913');
    await tester.enterText(find.byKey(const Key('pin-repeat')), '482913');
    await tester.tap(find.text('PIN speichern'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Therme warten'));
    await tester.pumpAndSettle();
    await tap(tester, find.byKey(const Key('open-report')));

    await tap(tester, find.byKey(const Key('add-time')));
    await tap(tester, find.byKey(const Key('save-time')));
    await tap(
      tester,
      find.descendant(of: find.byKey(const Key('check-wasser-yesno')), matching: find.text('Ja')),
    );
    await tap(tester, find.byKey(const Key('check-foto-photo')));
    await tap(tester, find.byKey(const Key('photo-hint-ok')));
    await tap(tester, find.byKey(const Key('save-photo')));
    await tester.enterText(find.byKey(const Key('work-description')), 'Brenner gereinigt.  ');
    await tap(tester, find.byKey(const Key('save-description')));
    await tap(tester, find.byKey(const Key('complete-report')));
    expect(find.byKey(const Key('to-preview')), findsOneWidget);
    device.syncServer.reportHash = (id) =>
        ReportCanonical.hash(serverSnapshot(device.syncServer, id));
  }

  testWidgets('the customer signs what the preview shows; the version is locked and the PDF made', (
    tester,
  ) async {
    await completedReport(tester);
    device.syncServer.offline = true; // signing works offline (M-NF-03)
    await tap(tester, find.byKey(const Key('to-preview')));
    expect(
      find.descendant(
        of: find.byKey(const Key('sign-preview')),
        matching: find.text('Therme warten'),
      ),
      findsOneWidget,
    );
    expect(
      find.text('Brenner gereinigt.'),
      findsOneWidget,
      reason: 'the preview shows the canonical text',
    );

    await tap(tester, find.byKey(const Key('to-signature')));
    await tap(tester, find.byKey(const Key('confirm-signature')));
    expect(find.byKey(const Key('signature-error')), findsOneWidget, reason: 'no signature yet');
    await tester.drag(find.byKey(const Key('signature-pad')), const Offset(200, 30));
    await tester.enterText(find.byKey(const Key('signer-name')), 'Maria Sommer');
    expect(
      find.text('sommer@example.test'),
      findsOneWidget,
      reason: 'the customer\'s email is suggested',
    );
    await tap(tester, find.byKey(const Key('confirm-signature')));

    expect(find.byKey(const Key('signed')), findsOneWidget);
    expect(find.byKey(const Key('qr-code')), findsOneWidget);
    final code = (tester.widget<SelectableText>(find.byKey(const Key('verification-code'))).data!)
        .split(' ')
        .last;
    expect(code, matches(RegExp(r'^[A-Z2-7]{10}$')));
    await tap(tester, find.byKey(const Key('share-pdf')));
    expect(device.sharedPdfs, ['Arbeitsbericht-$code.pdf']);
    await tap(tester, find.byKey(const Key('signed-done')));

    // Locked on the phone: nothing can be added any more.
    expect(find.byKey(const Key('report-signed')), findsOneWidget);
    expect(find.byKey(const Key('add-material')), findsNothing);
    expect(find.byKey(const Key('new-version')), findsOneWidget);
    expect(
      tester.widget<OutlinedButton>(find.byKey(const Key('new-version'))).onPressed,
      isNull,
      reason: 'not on the server yet',
    );

    // Online: the server's own hash matches the phone's.
    device.syncServer.offline = false;
    expect(
      await container(tester).read(syncCoordinatorProvider.notifier).syncNow(),
      SyncOutcome.done,
    );
    await tester.pumpAndSettle();
    final reportId = reportIdFor(wo);
    final signature = device.syncServer.data('report_signature', derivedId(reportId, 'signature'))!;
    expect(signature['kind'], 'SIGNED');
    expect(signature['signerName'], 'Maria Sommer');
    expect(signature['verificationCode'], code);
    expect(signature['customerEmail'], 'sommer@example.test');
    expect(
      signature['contentHash'],
      ReportCanonical.hash(serverSnapshot(device.syncServer, reportId)),
    );
    expect(device.syncServer.data('report', reportId)!['state'], 'SIGNED');

    // A correction: version 2.
    await tap(tester, find.byKey(const Key('new-version')));
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(device.syncServer.data('report', reportIdFor(wo, version: 2))!['version'], 2);
    expect(find.byKey(const Key('report-signed')), findsNothing, reason: 'version 2 is open');
    expect(find.byKey(const Key('add-material')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });

  testWidgets('sent without a signature, with a reason (M-BR-03)', (tester) async {
    await completedReport(tester);
    await tap(tester, find.byKey(const Key('to-preview')));
    await tap(tester, find.byKey(const Key('not-signed')));
    await tap(tester, find.byKey(const Key('confirm-signature')));
    expect(find.text('Bitte einen Grund angeben.'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('not-signed-reason')), 'Kundin nicht anwesend');
    await tap(tester, find.byKey(const Key('confirm-signature')));
    await tap(tester, find.byKey(const Key('signed-done')));
    expect(find.textContaining('ohne Unterschrift abgeschlossen'), findsOneWidget);
    expect(
      await container(tester).read(syncCoordinatorProvider.notifier).syncNow(),
      SyncOutcome.done,
    );
    await tester.pumpAndSettle();
    final signature = device.syncServer.data(
      'report_signature',
      derivedId(reportIdFor(wo), 'signature'),
    )!;
    expect(signature['kind'], 'NOT_SIGNED');
    expect(signature['notSignedReason'], 'Kundin nicht anwesend');
    expect(signature['signaturePng'], isNull);
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });

  testWidgets('a signature over content the server doesn\'t hold is refused and explained', (
    tester,
  ) async {
    await completedReport(tester);
    await tap(tester, find.byKey(const Key('to-preview')));
    // The office renamed the customer meanwhile: the server's hash differs from what was shown.
    device.syncServer.officeWrite('customer', 'c1', {'name': 'Familie Sommer-Weiß'});
    await tap(tester, find.byKey(const Key('to-signature')));
    await tester.drag(find.byKey(const Key('signature-pad')), const Offset(200, 30));
    await tester.enterText(find.byKey(const Key('signer-name')), 'Maria Sommer');
    await tap(tester, find.byKey(const Key('confirm-signature')));
    await tap(tester, find.byKey(const Key('signed-done')));
    await container(tester).read(syncCoordinatorProvider.notifier).syncNow();
    await tester.pumpAndSettle();
    expect(
      device.syncServer.data('report_signature', derivedId(reportIdFor(wo), 'signature')),
      isNull,
    );
    expect(find.byKey(const Key('report-signed')), findsNothing, reason: 'not signed after all');
    expect(find.byKey(const Key('to-preview')), findsOneWidget, reason: 'it can be signed again');
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  });
}
