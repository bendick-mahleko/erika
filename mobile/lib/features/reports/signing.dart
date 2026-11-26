import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:barcode/barcode.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:printing/printing.dart';

import '../../app/config.dart';
import '../../data/session/session.dart';
import '../../domain/rules/report_canonical.dart';
import '../../l10n/gen/app_localizations.dart';
import 'customer_pdf.dart';
import 'photos.dart';
import 'report_providers.dart';
import 'report_screen.dart';
import 'report_snapshot.dart';

/*
 * MARA Stage 7: the customer reviews and signs on the phone (M-MO-09, M-MO-10, M-MO-11, M-BR-03).
 * The preview shows exactly the canonical report that is hashed; the signature, the printed
 * name and the hash are stored together, and the version is locked.
 */

/// Renders the signature strokes to a PNG (overridden in tests, where images can't be drawn).
final signaturePngProvider =
    Provider<Future<Uint8List> Function(List<List<Offset>> strokes, Size size)>(
      (ref) => renderSignaturePng,
    );

/// Shares the customer PDF (mail, messenger, print); overridden in tests.
final pdfShareProvider = Provider<Future<void> Function(Uint8List pdf, String fileName)>(
  (ref) =>
      (pdf, fileName) => Printing.sharePdf(bytes: pdf, filename: fileName),
);

Future<Uint8List> renderSignaturePng(List<List<Offset>> strokes, Size size) async {
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  _paintStrokes(canvas, strokes, Colors.black);
  final image = await recorder.endRecording().toImage(size.width.ceil(), size.height.ceil());
  final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
  return bytes!.buffer.asUint8List();
}

void _paintStrokes(Canvas canvas, List<List<Offset>> strokes, Color color) {
  final paint = Paint()
    ..color = color
    ..strokeWidth = 3
    ..strokeCap = StrokeCap.round
    ..strokeJoin = StrokeJoin.round
    ..style = PaintingStyle.stroke;
  for (final stroke in strokes) {
    if (stroke.length == 1) canvas.drawCircle(stroke.first, 1.5, paint);
    final path = Path();
    for (var i = 0; i < stroke.length; i++) {
      i == 0 ? path.moveTo(stroke[i].dx, stroke[i].dy) : path.lineTo(stroke[i].dx, stroke[i].dy);
    }
    canvas.drawPath(path, paint);
  }
}

/// What the customer is about to sign: the canonical report, as the hash covers it.
class SignPreviewScreen extends ConsumerStatefulWidget {
  const SignPreviewScreen({required this.workOrderId, super.key});

  final String workOrderId;

  @override
  ConsumerState<SignPreviewScreen> createState() => _SignPreviewScreenState();
}

class _SignPreviewScreenState extends ConsumerState<SignPreviewScreen> {
  // Taken once when the preview opens: this exact content is shown, hashed and signed.
  Map<String, Object?>? _snapshot;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final view = ref.watch(reportViewProvider(widget.workOrderId));
    if (view == null) return Scaffold(appBar: AppBar(), body: Text(t.workOrderGone));
    final raw = _snapshot ??= reportSnapshot(view);
    return Scaffold(
      appBar: AppBar(title: Text(t.signPreviewTitle)),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.icon(
                key: const Key('to-signature'),
                style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
                icon: const Icon(Icons.draw),
                label: Text(t.letCustomerSign),
                onPressed: () => Navigator.of(context).pushReplacement(
                  MaterialPageRoute<void>(
                    builder: (_) => SignatureScreen(view: view, snapshot: raw),
                  ),
                ),
              ),
              TextButton(
                key: const Key('not-signed'),
                onPressed: () => Navigator.of(context).pushReplacement(
                  MaterialPageRoute<void>(
                    builder: (_) => SignatureScreen(view: view, snapshot: raw, unsigned: true),
                  ),
                ),
                child: Text(t.sendWithoutSignature),
              ),
            ],
          ),
        ),
      ),
      body: ListView(
        key: const Key('sign-preview'),
        padding: const EdgeInsets.all(16),
        children: [ReportPreview(snapshot: raw)],
      ),
    );
  }
}

/// The canonical report, readable (the same content as the customer PDF).
class ReportPreview extends StatelessWidget {
  const ReportPreview({required this.snapshot, super.key});

  final Map<String, Object?> snapshot;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final format = DateFormat('dd.MM.yyyy HH:mm', t.localeName);
    final style = Theme.of(context).textTheme;
    List<Map<String, Object?>> list(String key) => [
      for (final m in (snapshot[key] as List<dynamic>? ?? const []))
        (m as Map).cast<String, Object?>(),
    ];
    String s(Object? v) => (v as String?)?.trim() ?? '';
    Widget heading(String text) => Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 4),
      child: Text(text, style: style.titleMedium),
    );
    final workOrder = (snapshot['workOrder'] as Map).cast<String, Object?>();
    final customer = (snapshot['customer'] as Map).cast<String, Object?>();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(s(workOrder['title']), style: style.titleLarge),
        Text('${s(customer['name'])} · ${t.versionLabel(snapshot['version']! as int)}'),
        if (list('times').isNotEmpty) ...[
          heading(t.timesSection),
          for (final e in list('times'))
            Text(
              '${format.format(DateTime.parse(e['start']! as String).toLocal())} – '
              '${e['end'] == null ? t.timerRunning : DateFormat.Hm(t.localeName).format(DateTime.parse(e['end']! as String).toLocal())}'
              '  ${e['kind'] == 'TRAVEL' ? t.travelTime : t.workTime}  ${s(e['technician'])}',
            ),
        ],
        if (list('materials').isNotEmpty) ...[
          heading(t.materialsSection),
          for (final m in list('materials'))
            Text(
              '${localDecimal(t, m['quantity'] as String?)} ${unitLabel(t, s(m['unit']))}  ${s(m['description'])}',
            ),
        ],
        if (list('aufmass').isNotEmpty) ...[
          heading(t.aufmassSection),
          for (final p in list('aufmass'))
            Text(
              '${s(p['label'])}: ${localDecimal(t, p['result'] as String?)} ${unitLabel(t, s(p['unit']))}',
            ),
        ],
        if (list('checklist').isNotEmpty) ...[
          heading(t.checklistSection),
          for (final c in list('checklist'))
            Text(
              '${s(c['label'])}: ${switch (c['value']) {
                'YES' => t.yes,
                'NO' => t.no,
                null => '–',
                final v => s(v),
              }}',
            ),
        ],
        if (s(snapshot['workDescription']).isNotEmpty) ...[
          heading(t.workDescriptionSection),
          Text(s(snapshot['workDescription'])),
        ],
        if (list('photos').isNotEmpty) ...[
          heading(t.photosSection),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final p in list('photos'))
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    PhotoThumbnail(photoId: p['id']! as String, size: 96),
                    Text(photoCategoryLabel(t, s(p['category'])), style: style.bodySmall),
                  ],
                ),
            ],
          ),
        ],
      ],
    );
  }
}

/// The signature pad with printed name, comment and the customer's email (or, unsigned, the
/// reason).
class SignatureScreen extends ConsumerStatefulWidget {
  const SignatureScreen({
    required this.view,
    required this.snapshot,
    this.unsigned = false,
    super.key,
  });

  final ReportView view;
  final Map<String, Object?> snapshot;
  final bool unsigned;

  @override
  ConsumerState<SignatureScreen> createState() => _SignatureScreenState();
}

class _SignatureScreenState extends ConsumerState<SignatureScreen> {
  final _strokes = <List<Offset>>[];
  final _name = TextEditingController();
  final _comment = TextEditingController();
  final _reason = TextEditingController();
  late final _email = TextEditingController(
    text: widget.view.workOrder.customer?.text('email') ?? '',
  );
  static const _padSize = Size(600, 200);
  String? _error;
  var _busy = false;

  @override
  void dispose() {
    for (final c in [_name, _comment, _reason, _email]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _confirm() async {
    final t = AppLocalizations.of(context);
    if (!widget.unsigned && (_strokes.isEmpty || _name.text.trim().isEmpty)) {
      setState(() => _error = t.signatureIncomplete);
      return;
    }
    if (widget.unsigned && _reason.text.trim().isEmpty) {
      setState(() => _error = t.reasonRequired);
      return;
    }
    setState(() => _busy = true);
    final hash = ReportCanonical.hash(widget.snapshot);
    final code = newVerificationCode();
    final key = newDownloadKey();
    final png = widget.unsigned ? null : await ref.read(signaturePngProvider)(_strokes, _padSize);
    final email = _email.text.trim().isEmpty ? null : _email.text.trim();
    await ref
        .read(reportWriterProvider)
        .sign(
          widget.view,
          contentHash: hash,
          kind: widget.unsigned ? 'NOT_SIGNED' : 'SIGNED',
          verificationCode: code,
          downloadKey: key,
          signerName: widget.unsigned ? null : _name.text.trim(),
          comment: _comment.text.trim().isEmpty ? null : _comment.text.trim(),
          notSignedReason: widget.unsigned ? _reason.text.trim() : null,
          strokes: [
            for (final s in _strokes)
              [
                for (final p in s) [p.dx.round(), p.dy.round()],
              ],
          ],
          signaturePngBase64: png == null ? null : base64Encode(png),
          customerEmail: email,
        );
    final url = '${AppConfig.apiBaseUrl}/verify/$code?k=$key';
    final firm = switch (ref.read(sessionProvider).value) {
      Unlocked(:final user) => user.workspaceName,
      _ => '',
    };
    final pdf = await buildCustomerPdf(
      firmName: firm,
      report: widget.snapshot,
      signature: CustomerPdfSignature(
        kind: widget.unsigned ? 'NOT_SIGNED' : 'SIGNED',
        signedAt: ref.read(clockProvider)(),
        contentHash: hash,
        verificationCode: code,
        verificationUrl: url,
        signerName: widget.unsigned ? null : _name.text.trim(),
        comment: _comment.text.trim().isEmpty ? null : _comment.text.trim(),
        notSignedReason: widget.unsigned ? _reason.text.trim() : null,
        png: png,
      ),
    );
    if (!mounted) return;
    await Navigator.of(context).pushReplacement(
      MaterialPageRoute<void>(
        builder: (_) => SignedScreen(
          code: code,
          url: url,
          pdf: pdf,
          emailed: email != null,
          unsigned: widget.unsigned,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(widget.unsigned ? t.sendWithoutSignature : t.signatureTitle)),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: FilledButton(
            key: const Key('confirm-signature'),
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
            onPressed: _busy ? null : _confirm,
            child: Text(t.confirmSignature),
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (!widget.unsigned) ...[
            Text(t.signatureHint),
            const SizedBox(height: 8),
            AspectRatio(
              aspectRatio: _padSize.aspectRatio,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final scale = _padSize.width / constraints.maxWidth;
                  Offset point(Offset local) => local * scale;
                  return GestureDetector(
                    key: const Key('signature-pad'),
                    onPanStart: (d) => setState(() => _strokes.add([point(d.localPosition)])),
                    onPanUpdate: (d) => setState(() => _strokes.last.add(point(d.localPosition))),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        border: Border.all(color: Theme.of(context).colorScheme.outline),
                        color: Colors.white,
                      ),
                      child: CustomPaint(
                        painter: _PadPainter(_strokes, 1 / scale),
                        size: Size.infinite,
                      ),
                    ),
                  );
                },
              ),
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                key: const Key('clear-signature'),
                onPressed: () => setState(_strokes.clear),
                child: Text(t.clearSignature),
              ),
            ),
            TextField(
              key: const Key('signer-name'),
              controller: _name,
              textCapitalization: TextCapitalization.words,
              decoration: InputDecoration(labelText: t.signerName),
            ),
            TextField(
              key: const Key('signature-comment'),
              controller: _comment,
              decoration: InputDecoration(labelText: t.signatureComment),
            ),
          ] else
            TextField(
              key: const Key('not-signed-reason'),
              controller: _reason,
              minLines: 2,
              maxLines: 4,
              decoration: InputDecoration(labelText: t.notSignedReason),
            ),
          TextField(
            key: const Key('customer-email'),
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            decoration: InputDecoration(
              labelText: t.customerEmail,
              helperText: t.customerEmailHint,
              helperMaxLines: 2,
            ),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _error!,
                key: const Key('signature-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
    );
  }
}

class _PadPainter extends CustomPainter {
  _PadPainter(this.strokes, this.scale);

  final List<List<Offset>> strokes;
  final double scale;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(scale);
    _paintStrokes(canvas, strokes, Colors.black);
  }

  @override
  bool shouldRepaint(covariant _PadPainter oldDelegate) => true;
}

/// Done on site: the QR code to check the report and fetch the PDF later, and the PDF to share.
class SignedScreen extends ConsumerWidget {
  const SignedScreen({
    required this.code,
    required this.url,
    required this.pdf,
    required this.emailed,
    required this.unsigned,
    super.key,
  });

  final String code;
  final String url;
  final Uint8List pdf;
  final bool emailed;
  final bool unsigned;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(unsigned ? t.sentWithoutSignature : t.signedTitle)),
      body: ListView(
        key: const Key('signed'),
        padding: const EdgeInsets.all(16),
        children: [
          Text(emailed ? t.pdfWillBeEmailed : t.pdfNotEmailed),
          const SizedBox(height: 16),
          Center(
            child: Container(
              color: Colors.white,
              padding: const EdgeInsets.all(12),
              child: CustomPaint(
                key: const Key('qr-code'),
                size: const Size.square(200),
                painter: _QrPainter(url),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Center(child: Text(t.qrHint)),
          Center(
            child: SelectableText(
              t.verificationCodeLabel(code),
              key: const Key('verification-code'),
            ),
          ),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            key: const Key('share-pdf'),
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
            icon: const Icon(Icons.share),
            label: Text(t.sharePdf),
            onPressed: () => ref.read(pdfShareProvider)(pdf, 'Arbeitsbericht-$code.pdf'),
          ),
          const SizedBox(height: 8),
          FilledButton(
            key: const Key('signed-done'),
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
            onPressed: () => Navigator.pop(context),
            child: Text(t.done),
          ),
        ],
      ),
    );
  }
}

class _QrPainter extends CustomPainter {
  _QrPainter(this.data);

  final String data;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = Colors.black;
    for (final e in Barcode.qrCode().make(data, width: size.width, height: size.height)) {
      if (e is BarcodeBar && e.black) {
        canvas.drawRect(Rect.fromLTWH(e.left, e.top, e.width, e.height), paint);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _QrPainter oldDelegate) => oldDelegate.data != data;
}
