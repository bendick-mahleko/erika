import 'dart:math';

import '../../domain/rules/report_canonical.dart';
import 'report_screen.dart' show localResult;
import 'report_providers.dart';

/// The canonical report input from the phone's records (M-MO-09): exactly what the preview shows
/// and the customer signs. The server builds the same from its data (`ReportSnapshots`); both
/// give the same text with the shared rules (`mara-rules/vectors/report/canonical.json`).
Map<String, Object?> reportSnapshot(ReportView view) => {
  'format': ReportCanonical.format,
  'reportId': view.reportId,
  'version': view.version,
  'workOrder': {'id': view.workOrder.id, 'title': view.workOrder.title},
  'customer': {'name': view.workOrder.customer?.text('name')},
  'workDescription': view.workDescription,
  'times': [
    for (final t in view.times)
      {
        'id': t.id,
        'technician': view.staffNames[t.text('technicianId')],
        'kind': t.text('kind') ?? 'WORK',
        'start': t.text('startedAt'),
        'end': t.text('endedAt'),
        'note': t.text('note'),
      },
  ],
  'materials': [
    for (final m in view.materials)
      {
        'id': m.id,
        'description': m.text('description'),
        'quantity': m.text('quantity'),
        'unit': m.text('unitCode'),
      },
  ],
  'aufmass': [
    for (final p in view.aufmass)
      {
        'id': p.id,
        'label': p.text('label'),
        'kind': p.text('kind'),
        'length': p.text('length'),
        'width': p.text('width'),
        'height': p.text('height'),
        'count': p.text('count'),
        'expression': p.text('expression'),
        'deductions': [
          for (final d
              in (p.data['deductions'] as List<dynamic>? ?? const []).cast<Map<String, dynamic>>())
            {
              'label': d['label'],
              'length': d['length'],
              'width': d['width'],
              'height': d['height'],
              'count': d['count'],
            },
        ],
        // The server calculates it with the same rules; until confirmed, the phone's own result.
        'result': p.pending ? localResult(p) : (p.text('result') ?? localResult(p)),
        'unit': p.text('unitCode'),
      },
  ],
  // Photo items are answered by the photos below.
  'checklist': [
    for (final i in view.checklist)
      if (i.kind != 'PHOTO') {'key': i.key, 'label': i.label, 'value': i.value},
  ],
  'photos': [
    for (final p in view.photos)
      {
        'id': p.id,
        'category': p.text('category'),
        'caption': p.text('caption'),
        'checklistItemKey': p.text('checklistItemKey'),
        'sha256': p.text('sha256'),
      },
  ],
};

const _base32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const _urlSafe = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

/// The code printed on the PDF for the public check (10 characters, about 50 bits).
String newVerificationCode([Random? random]) {
  final r = random ?? Random.secure();
  return List.generate(10, (_) => _base32[r.nextInt(32)]).join();
}

/// The key in the QR code that lets the customer fetch the PDF later (32 characters, 192 bits).
String newDownloadKey([Random? random]) {
  final r = random ?? Random.secure();
  return List.generate(32, (_) => _urlSafe[r.nextInt(64)]).join();
}
