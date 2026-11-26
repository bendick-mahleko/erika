import 'dart:typed_data';

import 'package:intl/intl.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

/// What the customer PDF shows besides the report itself.
class CustomerPdfSignature {
  const CustomerPdfSignature({
    required this.kind,
    required this.signedAt,
    required this.contentHash,
    required this.verificationCode,
    required this.verificationUrl,
    this.signerName,
    this.comment,
    this.notSignedReason,
    this.png,
  });

  final String kind;
  final DateTime signedAt;
  final String contentHash;
  final String verificationCode;

  /// The QR code's target: the public check, with the key to fetch the PDF later (M-MO-11).
  final String verificationUrl;
  final String? signerName;
  final String? comment;
  final String? notSignedReason;
  final Uint8List? png;
}

/// The customer's PDF, rendered on the phone right after signing (M-MO-11), offline: the
/// canonical report as previewed, the signature, its SHA-256, the verification code and a QR
/// code. The archive keeps the server's PDF of the same content (ADR 0040).
Future<Uint8List> buildCustomerPdf({
  required String firmName,
  required Map<String, Object?> report,
  required CustomerPdfSignature signature,
}) async {
  final doc = pw.Document(title: 'Arbeitsbericht', creator: 'MARA');
  final when = DateFormat('dd.MM.yyyy HH:mm');
  // The PDF's standard fonts cover Latin-1; anything else (e.g. emoji) becomes "?".
  String text(Object? v) =>
      String.fromCharCodes(((v as String?)?.trim() ?? '').runes.map((r) => r <= 0xff ? r : 0x3f));
  String decimal(Object? v) => text(v).replaceAll('.', ',');
  List<Map<String, Object?>> list(String key) => [
    for (final m in (report[key] as List<dynamic>? ?? const [])) (m as Map).cast<String, Object?>(),
  ];
  String local(Object? iso) =>
      iso == null ? '' : when.format(DateTime.parse(iso as String).toLocal());
  const units = {
    'C62': 'Stück',
    'MTR': 'm',
    'MTK': 'm²',
    'MTQ': 'm³',
    'KGM': 'kg',
    'LTR': 'l',
    'HUR': 'Std.',
    'LS': 'pauschal',
  };

  pw.Widget heading(String s) => pw.Padding(
    padding: const pw.EdgeInsets.only(top: 10, bottom: 4),
    child: pw.Text(s, style: pw.TextStyle(fontWeight: pw.FontWeight.bold, fontSize: 12)),
  );

  final workOrder = (report['workOrder'] as Map?)?.cast<String, Object?>() ?? const {};
  final customer = (report['customer'] as Map?)?.cast<String, Object?>() ?? const {};
  doc.addPage(
    pw.MultiPage(
      pageFormat: PdfPageFormat.a4,
      margin: const pw.EdgeInsets.all(40),
      footer: (context) => pw.Text(
        'SHA-256: ${signature.contentHash}\nPrüfcode ${signature.verificationCode} - Seite ${context.pageNumber}/${context.pagesCount}',
        style: const pw.TextStyle(fontSize: 7),
      ),
      build: (context) => [
        pw.Text(text(firmName), style: pw.TextStyle(fontWeight: pw.FontWeight.bold)),
        pw.SizedBox(height: 8),
        pw.Text(
          'Arbeitsbericht',
          style: pw.TextStyle(fontSize: 18, fontWeight: pw.FontWeight.bold),
        ),
        pw.Text('Auftrag: ${text(workOrder['title'])}'),
        pw.Text('Kunde: ${text(customer['name'])}'),
        pw.Text('Version ${report['version']}'),
        if (list('times').isNotEmpty) ...[
          heading('Zeiten'),
          for (final t in list('times'))
            pw.Text(
              '${local(t['start'])} - ${t['end'] == null ? 'läuft' : DateFormat('HH:mm').format(DateTime.parse(t['end']! as String).toLocal())}  '
              '${t['kind'] == 'TRAVEL' ? 'Fahrt' : 'Arbeit'}  ${text(t['technician'])}',
            ),
        ],
        if (list('materials').isNotEmpty) ...[
          heading('Material'),
          for (final m in list('materials'))
            pw.Text(
              '${decimal(m['quantity'])} ${units[m['unit']] ?? text(m['unit'])}  ${text(m['description'])}',
            ),
        ],
        if (list('aufmass').isNotEmpty) ...[
          heading('Aufmaß'),
          for (final p in list('aufmass'))
            pw.Text(
              '${text(p['label'])}: ${decimal(p['result'])} ${units[p['unit']] ?? text(p['unit'])}',
            ),
        ],
        if (list('checklist').isNotEmpty) ...[
          heading('Checkliste'),
          for (final c in list('checklist'))
            pw.Text(
              '${text(c['label'])}: ${switch (c['value']) {
                'YES' => 'Ja',
                'NO' => 'Nein',
                null => '-',
                final v => text(v),
              }}',
            ),
        ],
        if (text(report['workDescription']).isNotEmpty) ...[
          heading('Arbeitsbeschreibung'),
          pw.Text(text(report['workDescription'])),
        ],
        if (list('photos').isNotEmpty) ...[
          heading('Fotos'),
          for (final p in list('photos'))
            pw.Text([p['category'], p['caption']].whereType<String>().join(': ')),
        ],
        heading(signature.kind == 'SIGNED' ? 'Abnahme' : 'Ohne Unterschrift'),
        if (signature.png != null) pw.Image(pw.MemoryImage(signature.png!), height: 70),
        if (signature.kind == 'SIGNED')
          pw.Text('${text(signature.signerName)}, ${when.format(signature.signedAt.toLocal())}')
        else
          pw.Text(
            'Nicht unterschrieben am ${when.format(signature.signedAt.toLocal())}. Grund: ${text(signature.notSignedReason)}',
          ),
        if (signature.comment != null) pw.Text('Anmerkung: ${text(signature.comment)}'),
        pw.SizedBox(height: 12),
        pw.Row(
          crossAxisAlignment: pw.CrossAxisAlignment.start,
          children: [
            pw.BarcodeWidget(
              barcode: pw.Barcode.qrCode(),
              data: signature.verificationUrl,
              width: 80,
              height: 80,
            ),
            pw.SizedBox(width: 12),
            pw.Expanded(
              child: pw.Text(
                'Echtheit prüfen und den Bericht später abrufen: QR-Code scannen oder ${signature.verificationUrl.split('?').first} öffnen.',
                style: const pw.TextStyle(fontSize: 9),
              ),
            ),
          ],
        ),
      ],
    ),
  );
  return doc.save();
}
