import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../data/session/session.dart';
import '../../data/sync/sync_store.dart';
import '../../l10n/gen/app_localizations.dart';
import 'report_providers.dart';

/// Enters a time afterwards or changes one (M-MO-03): work or travel, for whom, from – to.
/// The times are stored as entered, unrounded (M-BR-04).
Future<void> showTimeEntryDialog(
  BuildContext context,
  WidgetRef ref, {
  required ReportView view,
  LocalRecord? entry,
}) => showDialog<void>(
  context: context,
  builder: (_) => _TimeEntryDialog(view: view, entry: entry),
);

class _TimeEntryDialog extends ConsumerStatefulWidget {
  const _TimeEntryDialog({required this.view, this.entry});

  final ReportView view;
  final LocalRecord? entry;

  @override
  ConsumerState<_TimeEntryDialog> createState() => _TimeEntryDialogState();
}

class _TimeEntryDialogState extends ConsumerState<_TimeEntryDialog> {
  late String _kind;
  late String? _technician;
  late DateTime _start;
  DateTime? _end;
  late final _note = TextEditingController(text: widget.entry?.text('note') ?? '');
  String? _error;

  @override
  void initState() {
    super.initState();
    final e = widget.entry;
    final now = ref.read(clockProvider)();
    _kind = e?.text('kind') ?? 'WORK';
    _technician = e?.text('technicianId') ?? ref.read(currentUserIdProvider);
    _start = e == null
        ? DateTime(now.year, now.month, now.day, now.hour).subtract(const Duration(hours: 1))
        : DateTime.parse(e.text('startedAt')!).toLocal();
    final end = e?.text('endedAt');
    _end = e == null
        ? _start.add(const Duration(hours: 1))
        : (end == null ? null : DateTime.parse(end).toLocal());
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  /// The people who can have times here: the assignees, the signed-in user, the current one.
  List<String> _people() {
    final me = ref.read(currentUserIdProvider);
    return {...widget.view.workOrder.assigneeIds, ?me, ?_technician}.toList();
  }

  Future<void> _pickTime(bool start) async {
    final current = (start ? _start : _end) ?? _start;
    final date = await showDatePicker(
      context: context,
      initialDate: current,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(current),
    );
    if (time == null) return;
    final value = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    setState(() => start ? _start = value : _end = value);
  }

  Future<void> _save() async {
    final t = AppLocalizations.of(context);
    if (_end != null && _end!.isBefore(_start)) {
      setState(() => _error = t.endBeforeStart);
      return;
    }
    final values = {
      'technicianId': _technician,
      'kind': _kind,
      'startedAt': _start.toUtc().toIso8601String(),
      'endedAt': _end?.toUtc().toIso8601String(),
      'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
    };
    final writer = ref.read(reportWriterProvider);
    if (widget.entry == null) {
      await writer.add(widget.view.workOrder.id, 'time_entry', values);
    } else {
      await writer.change('time_entry', widget.entry!.id, values);
    }
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final format = DateFormat.yMMMd(t.localeName).add_Hm();
    return AlertDialog(
      key: const Key('time-entry-dialog'),
      title: Text(widget.entry == null ? t.addTimeManually : t.editTime),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'WORK', label: Text(t.workTime)),
                ButtonSegment(value: 'TRAVEL', label: Text(t.travelTime)),
              ],
              selected: {_kind},
              onSelectionChanged: (s) => setState(() => _kind = s.first),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              key: const Key('time-technician'),
              initialValue: _technician,
              decoration: InputDecoration(labelText: t.technicianLabel),
              items: [
                for (final id in _people())
                  DropdownMenuItem(
                    value: id,
                    child: Text(widget.view.staffNames[id] ?? t.unknownPerson),
                  ),
              ],
              onChanged: (v) => setState(() => _technician = v),
            ),
            const SizedBox(height: 12),
            ListTile(
              key: const Key('time-start'),
              contentPadding: EdgeInsets.zero,
              title: Text(t.fromLabel),
              subtitle: Text(format.format(_start)),
              onTap: () => _pickTime(true),
            ),
            ListTile(
              key: const Key('time-end'),
              contentPadding: EdgeInsets.zero,
              title: Text(t.toLabel),
              subtitle: Text(_end == null ? t.timerRunning : format.format(_end!)),
              onTap: () => _pickTime(false),
            ),
            TextField(
              key: const Key('time-note'),
              controller: _note,
              decoration: InputDecoration(labelText: t.noteLabel),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
          ],
        ),
      ),
      actions: [
        if (widget.entry != null)
          TextButton(
            key: const Key('delete-time'),
            onPressed: () async {
              await ref.read(reportWriterProvider).delete('time_entry', widget.entry!.id);
              if (context.mounted) Navigator.pop(context);
            },
            child: Text(t.deleteButton),
          ),
        TextButton(onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(key: const Key('save-time'), onPressed: _save, child: Text(t.saveButton)),
      ],
    );
  }
}
