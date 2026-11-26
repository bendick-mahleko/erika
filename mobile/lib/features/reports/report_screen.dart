import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../data/session/session.dart';
import '../../data/sync/sync_store.dart';
import '../../l10n/gen/app_localizations.dart';
import 'aufmass_editor_screen.dart';
import 'material_picker.dart';
import 'photos.dart';
import 'report_providers.dart';
import 'time_entry_dialog.dart';

/// The units a technician picks for free-text materials and Aufmaß (UN/ECE Rec 20 codes,
/// which the invoice needs later).
const unitCodes = ['C62', 'MTR', 'MTK', 'MTQ', 'KGM', 'LTR', 'HUR', 'LS'];

String unitLabel(AppLocalizations t, String code) => switch (code) {
  'C62' => t.unitPiece,
  'MTR' => 'm',
  'MTK' => 'm²',
  'MTQ' => 'm³',
  'KGM' => 'kg',
  'LTR' => 'l',
  'HUR' => t.unitHour,
  'LS' => t.unitLumpSum,
  _ => code,
};

/// A decimal from the server ("2.5") as the user reads it ("2,5" in German).
String localDecimal(AppLocalizations t, String? value) =>
    value == null ? '' : (t.localeName.startsWith('de') ? value.replaceAll('.', ',') : value);

/// Hours and minutes of a duration ("1:05 h"); raw times stay unrounded (M-BR-04).
String hoursMinutes(Duration d) {
  final minutes = d.inMinutes;
  return '${minutes ~/ 60}:${(minutes % 60).toString().padLeft(2, '0')} h';
}

/// The job report of a work order (M-MO-03 to M-MO-08): times, materials, Aufmaß, checklist
/// and work description. Works offline; the main action sits at the bottom for one hand.
class ReportScreen extends ConsumerWidget {
  const ReportScreen({required this.workOrderId, super.key});

  final String workOrderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final view = ref.watch(reportViewProvider(workOrderId));
    if (view == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Padding(padding: const EdgeInsets.all(16), child: Text(t.workOrderGone)),
      );
    }
    final editable = !view.locked && !view.completed;
    return Scaffold(
      appBar: AppBar(title: Text(t.reportTitle(view.workOrder.title))),
      bottomNavigationBar: view.locked
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: view.completed
                    ? OutlinedButton.icon(
                        key: const Key('reopen-report'),
                        style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(56)),
                        icon: const Icon(Icons.lock_open),
                        label: Text(t.reopenReport),
                        onPressed: () => ref.read(reportWriterProvider).changeReport(workOrderId, {
                          'state': 'OPEN',
                        }),
                      )
                    : FilledButton.icon(
                        key: const Key('complete-report'),
                        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
                        icon: const Icon(Icons.check),
                        label: Text(t.completeReport),
                        onPressed: () => _complete(context, ref, view),
                      ),
              ),
            ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        children: [
          if (view.completed)
            Card(
              key: const Key('report-completed'),
              child: ListTile(leading: const Icon(Icons.task_alt), title: Text(t.reportCompleted)),
            ),
          _TimesSection(view: view, editable: editable),
          const Divider(height: 32),
          _MaterialsSection(view: view, editable: editable),
          const Divider(height: 32),
          _AufmassSection(view: view, editable: editable),
          const Divider(height: 32),
          PhotosSection(view: view, editable: editable),
          if (view.checklist.isNotEmpty) ...[
            const Divider(height: 32),
            _ChecklistSection(view: view, editable: editable),
          ],
          const Divider(height: 32),
          _DescriptionSection(view: view, editable: editable),
        ],
      ),
    );
  }

  Future<void> _complete(BuildContext context, WidgetRef ref, ReportView view) async {
    final t = AppLocalizations.of(context);
    if (!view.completable) {
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          key: const Key('cannot-complete'),
          title: Text(t.cannotCompleteTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (view.runningTimers.isNotEmpty) Text('• ${t.timerStillRunning}'),
              for (final item in view.missingItems) Text('• ${t.checklistItemMissing(item.label)}'),
            ],
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(context), child: Text(t.okButton))],
        ),
      );
      return;
    }
    final writer = ref.read(reportWriterProvider);
    await writer.changeReport(workOrderId, {'state': 'COMPLETED'});
    if (view.workOrder.status != 'DONE') {
      await writer.change('work_order', workOrderId, {'status': 'DONE'});
    }
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(t.reportCompletedSaved)));
    }
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Row(
      children: [
        Expanded(
          child: Semantics(
            header: true,
            child: Text(text, style: Theme.of(context).textTheme.titleMedium),
          ),
        ),
      ],
    ),
  );
}

/// Times for work and travel (M-MO-03): a timer or entered afterwards, for several technicians.
class _TimesSection extends ConsumerWidget {
  const _TimesSection({required this.view, required this.editable});

  final ReportView view;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final me = ref.watch(currentUserIdProvider);
    final office = ref.watch(isOfficeUserProvider);
    final now = ref.watch(clockProvider);
    final format = DateFormat.Hm(t.localeName);
    final day = DateFormat.MMMd(t.localeName);
    final myTimer = view.runningTimers
        .where((e) => e.text('authorId') == me || e.text('authorId') == null)
        .firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(t.timesSection),
        for (final e in view.times)
          Builder(
            builder: (context) {
              final start = DateTime.parse(e.text('startedAt')!).toLocal();
              final endText = e.text('endedAt');
              final end = endText == null ? null : DateTime.parse(endText).toLocal();
              final mine = office || e.text('authorId') == null || e.text('authorId') == me;
              final kind = e.text('kind') == 'TRAVEL' ? t.travelTime : t.workTime;
              final who = view.staffNames[e.text('technicianId')] ?? '';
              return ListTile(
                key: Key('time-${e.id}'),
                contentPadding: EdgeInsets.zero,
                minTileHeight: 56,
                leading: Icon(e.text('kind') == 'TRAVEL' ? Icons.directions_car : Icons.build),
                title: Text(
                  '$kind · ${hoursMinutes((end ?? now()).difference(start))}${end == null ? ' · ${t.timerRunning}' : ''}',
                ),
                subtitle: Text(
                  [
                    '${day.format(start)}, ${format.format(start)} – ${end == null ? '…' : format.format(end)}',
                    if (who.isNotEmpty) who,
                    if (e.text('note') != null) e.text('note')!,
                  ].join(' · '),
                ),
                trailing: e.pending ? const Icon(Icons.cloud_upload_outlined, size: 18) : null,
                onTap: editable && mine
                    ? () => showTimeEntryDialog(context, ref, view: view, entry: e)
                    : null,
              );
            },
          ),
        if (editable)
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (myTimer != null)
                FilledButton.icon(
                  key: const Key('stop-timer'),
                  style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
                  icon: const Icon(Icons.stop),
                  label: Text(t.stopTimer),
                  onPressed: () => ref.read(reportWriterProvider).change('time_entry', myTimer.id, {
                    'endedAt': now().toUtc().toIso8601String(),
                  }),
                )
              else ...[
                FilledButton.icon(
                  key: const Key('start-work-timer'),
                  style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
                  icon: const Icon(Icons.play_arrow),
                  label: Text(t.startWorkTimer),
                  onPressed: () => _start(ref, 'WORK', me, now),
                ),
                OutlinedButton.icon(
                  key: const Key('start-travel-timer'),
                  style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                  icon: const Icon(Icons.directions_car),
                  label: Text(t.startTravelTimer),
                  onPressed: () => _start(ref, 'TRAVEL', me, now),
                ),
              ],
              OutlinedButton.icon(
                key: const Key('add-time'),
                style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                icon: const Icon(Icons.edit_calendar),
                label: Text(t.addTimeManually),
                onPressed: () => showTimeEntryDialog(context, ref, view: view),
              ),
            ],
          ),
      ],
    );
  }

  Future<void> _start(WidgetRef ref, String kind, String? me, DateTime Function() now) =>
      ref.read(reportWriterProvider).add(view.workOrder.id, 'time_entry', {
        'technicianId': me,
        'kind': kind,
        'startedAt': now().toUtc().toIso8601String(),
        'endedAt': null,
      });
}

/// Materials (M-MO-04).
class _MaterialsSection extends ConsumerWidget {
  const _MaterialsSection({required this.view, required this.editable});

  final ReportView view;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final me = ref.watch(currentUserIdProvider);
    final office = ref.watch(isOfficeUserProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(t.materialsSection),
        for (final m in view.materials)
          ListTile(
            key: Key('material-${m.id}'),
            contentPadding: EdgeInsets.zero,
            minTileHeight: 56,
            title: Text(m.text('description') ?? ''),
            subtitle: Text(
              '${localDecimal(t, m.text('quantity'))} ${unitLabel(t, m.text('unitCode') ?? '')}',
            ),
            trailing: m.pending ? const Icon(Icons.cloud_upload_outlined, size: 18) : null,
            onTap: editable && (office || m.text('authorId') == null || m.text('authorId') == me)
                ? () => showMaterialEditDialog(context, ref, m)
                : null,
          ),
        if (editable)
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              key: const Key('add-material'),
              style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
              icon: const Icon(Icons.add_shopping_cart),
              label: Text(t.addMaterial),
              onPressed: () => showMaterialPicker(context, ref, workOrderId: view.workOrder.id),
            ),
          ),
      ],
    );
  }
}

/// Aufmaß (M-MO-06).
class _AufmassSection extends ConsumerWidget {
  const _AufmassSection({required this.view, required this.editable});

  final ReportView view;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final me = ref.watch(currentUserIdProvider);
    final office = ref.watch(isOfficeUserProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(t.aufmassSection),
        for (final p in view.aufmass)
          ListTile(
            key: Key('aufmass-${p.id}'),
            contentPadding: EdgeInsets.zero,
            minTileHeight: 56,
            title: Text(p.text('label') ?? ''),
            trailing: Text(
              // The server's result once confirmed; the phone's own calculation until then.
              '${localDecimal(t, p.pending ? localResult(p) : p.text('result') ?? localResult(p))} ${unitLabel(t, p.text('unitCode') ?? '')}',
              key: Key('aufmass-result-${p.id}'),
            ),
            onTap: editable && (office || p.text('authorId') == null || p.text('authorId') == me)
                ? () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) =>
                          AufmassEditorScreen(workOrderId: view.workOrder.id, position: p),
                    ),
                  )
                : null,
          ),
        if (editable)
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              key: const Key('add-aufmass'),
              style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
              icon: const Icon(Icons.straighten),
              label: Text(t.addAufmass),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => AufmassEditorScreen(workOrderId: view.workOrder.id),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// Checklist with mandatory items (M-MO-07).
class _ChecklistSection extends ConsumerWidget {
  const _ChecklistSection({required this.view, required this.editable});

  final ReportView view;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final writer = ref.read(reportWriterProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(t.checklistSection),
        for (final item in view.checklist)
          Padding(
            key: Key('check-${item.key}'),
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.required ? '${item.label} *' : item.label),
                const SizedBox(height: 4),
                switch (item.kind) {
                  'YES_NO' => SegmentedButton<String>(
                    key: Key('check-${item.key}-yesno'),
                    segments: [
                      ButtonSegment(value: 'YES', label: Text(t.yes)),
                      ButtonSegment(value: 'NO', label: Text(t.no)),
                    ],
                    selected: {?item.value},
                    emptySelectionAllowed: true,
                    onSelectionChanged: editable
                        ? (s) => writer.answer(view.workOrder.id, item.key, s.firstOrNull)
                        : null,
                  ),
                  'PHOTO' => Row(
                    children: [
                      FilledButton.tonalIcon(
                        key: Key('check-${item.key}-photo'),
                        style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
                        icon: const Icon(Icons.photo_camera),
                        label: Text(t.takePhoto),
                        onPressed: editable
                            ? () => takePhoto(
                                context,
                                ref,
                                workOrderId: view.workOrder.id,
                                checklistItemKey: item.key,
                              )
                            : null,
                      ),
                      const SizedBox(width: 12),
                      if (item.answered) Text(t.photosForItem(int.parse(item.value!))),
                    ],
                  ),
                  _ => _AnswerField(
                    key: Key('check-${item.key}-text'),
                    initial: item.value ?? '',
                    enabled: editable,
                    multiline: item.kind == 'COMMENT',
                    onSave: (v) => writer.answer(
                      view.workOrder.id,
                      item.key,
                      v.trim().isEmpty ? null : v.trim(),
                    ),
                  ),
                },
              ],
            ),
          ),
      ],
    );
  }
}

class _AnswerField extends StatefulWidget {
  const _AnswerField({
    required this.initial,
    required this.enabled,
    required this.multiline,
    required this.onSave,
    super.key,
  });

  final String initial;
  final bool enabled;
  final bool multiline;
  final void Function(String) onSave;

  @override
  State<_AnswerField> createState() => _AnswerFieldState();
}

class _AnswerFieldState extends State<_AnswerField> {
  late final _controller = TextEditingController(text: widget.initial);
  final _focus = FocusNode();
  late String _saved = widget.initial;

  /// Saves once per new text (on "done" and again when the field loses focus).
  void _save() {
    if (_controller.text == _saved) return;
    _saved = _controller.text;
    widget.onSave(_controller.text);
  }

  @override
  void initState() {
    super.initState();
    // Saved when the field loses focus, so nothing typed is lost when moving on.
    _focus.addListener(() {
      if (!_focus.hasFocus) _save();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
    controller: _controller,
    focusNode: _focus,
    enabled: widget.enabled,
    minLines: widget.multiline ? 2 : 1,
    maxLines: widget.multiline ? 5 : 1,
    textInputAction: widget.multiline ? TextInputAction.newline : TextInputAction.done,
    onSubmitted: (_) => _save(),
    decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true),
  );
}

/// Work description, typed or dictated with the keyboard's microphone (M-MO-08).
class _DescriptionSection extends ConsumerStatefulWidget {
  const _DescriptionSection({required this.view, required this.editable});

  final ReportView view;
  final bool editable;

  @override
  ConsumerState<_DescriptionSection> createState() => _DescriptionSectionState();
}

class _DescriptionSectionState extends ConsumerState<_DescriptionSection> {
  late final _controller = TextEditingController(text: widget.view.workDescription ?? '');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionTitle(t.workDescriptionSection),
        TextField(
          key: const Key('work-description'),
          controller: _controller,
          enabled: widget.editable,
          minLines: 4,
          maxLines: 12,
          keyboardType: TextInputType.multiline,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(
            border: const OutlineInputBorder(),
            helperText: t.dictationHint,
            helperMaxLines: 2,
          ),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton(
            key: const Key('save-description'),
            style: FilledButton.styleFrom(minimumSize: const Size(120, 48)),
            onPressed: widget.editable
                ? () async {
                    final text = _controller.text.trim();
                    await ref.read(reportWriterProvider).changeReport(widget.view.workOrder.id, {
                      'workDescription': text.isEmpty ? null : text,
                    });
                    if (context.mounted) {
                      ScaffoldMessenger.of(
                        context,
                      ).showSnackBar(SnackBar(content: Text(t.savedLocally)));
                    }
                  }
                : null,
            child: Text(t.saveButton),
          ),
        ),
      ],
    );
  }
}

/// The phone's own result for a position not yet confirmed by the server.
String? localResult(LocalRecord p) => aufmassResultOf(p.data);
