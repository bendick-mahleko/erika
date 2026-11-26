import 'dart:convert';

import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/sync/sync_store.dart';
import '../../domain/rules/aufmass.dart';
import '../../l10n/gen/app_localizations.dart';
import 'report_providers.dart';
import 'report_screen.dart';

/// The default unit of each kind of position.
const _defaultUnit = {
  'LENGTH': 'MTR',
  'AREA': 'MTK',
  'VOLUME': 'MTQ',
  'PIECE': 'C62',
  'FORMULA': 'MTK',
};

Decimal? _dec(Object? v) => v is String && v.isNotEmpty ? Decimal.tryParse(v) : null;

/// The result of a position's data with the shared rules, as the server writes it ("9.672"),
/// or null when the data is incomplete or breaks a rule.
String? aufmassResultOf(Map<String, dynamic> data) {
  try {
    return AufmassRules.format(AufmassRules.position(_input(data)));
  } on AufmassException {
    return null;
  }
}

AufmassInput _input(Map<String, dynamic> data) => AufmassInput(
  kind: data['kind'] as String? ?? 'AREA',
  length: _dec(data['length']),
  width: _dec(data['width']),
  height: _dec(data['height']),
  count: _dec(data['count']),
  expression: data['expression'] as String?,
  deductions: [
    for (final d in (data['deductions'] as List<dynamic>? ?? const []).cast<Map<String, dynamic>>())
      AufmassDeductionInput(
        length: _dec(d['length']),
        width: _dec(d['width']),
        height: _dec(d['height']),
        count: _dec(d['count']),
      ),
  ],
);

class _DeductionFields {
  _DeductionFields([Map<String, dynamic>? d])
    : label = TextEditingController(text: d?['label'] as String? ?? ''),
      length = TextEditingController(text: d?['length'] as String? ?? ''),
      width = TextEditingController(text: d?['width'] as String? ?? ''),
      height = TextEditingController(text: d?['height'] as String? ?? ''),
      count = TextEditingController(text: d?['count'] as String? ?? '');

  final TextEditingController label;
  final TextEditingController length;
  final TextEditingController width;
  final TextEditingController height;
  final TextEditingController count;

  void dispose() {
    for (final c in [label, length, width, height, count]) {
      c.dispose();
    }
  }
}

/// Edits an Aufmaß position (M-MO-06): length, area, volume, pieces or a formula, with count
/// and deductions. The result is calculated on the phone with the same rules as the server.
class AufmassEditorScreen extends ConsumerStatefulWidget {
  const AufmassEditorScreen({required this.workOrderId, this.position, super.key});

  final String workOrderId;
  final LocalRecord? position;

  @override
  ConsumerState<AufmassEditorScreen> createState() => _AufmassEditorScreenState();
}

class _AufmassEditorScreenState extends ConsumerState<AufmassEditorScreen> {
  late final Map<String, dynamic> _initial = widget.position?.data ?? const {};
  late String _kind = _initial['kind'] as String? ?? 'AREA';
  late String _unit = _initial['unitCode'] as String? ?? _defaultUnit[_kind]!;
  late final _label = TextEditingController(text: _initial['label'] as String? ?? '');
  late final _length = TextEditingController(text: _initial['length'] as String? ?? '');
  late final _width = TextEditingController(text: _initial['width'] as String? ?? '');
  late final _height = TextEditingController(text: _initial['height'] as String? ?? '');
  late final _count = TextEditingController(text: _initial['count'] as String? ?? '');
  late final _expression = TextEditingController(text: _initial['expression'] as String? ?? '');
  late final List<_DeductionFields> _deductions = [
    for (final d
        in (_initial['deductions'] as List<dynamic>? ?? const []).cast<Map<String, dynamic>>())
      _DeductionFields(d),
  ];
  String? _error;

  @override
  void dispose() {
    for (final c in [_label, _length, _width, _height, _count, _expression]) {
      c.dispose();
    }
    for (final d in _deductions) {
      d.dispose();
    }
    super.dispose();
  }

  int get _dims => AufmassRules.dimensions[_kind] ?? 0;
  bool get _hasDeductions => _kind != 'PIECE' && _kind != 'FORMULA';

  /// The position as the server expects it; [FormatException] for a number that isn't one.
  Map<String, dynamic> _values() {
    String? n(TextEditingController c) => AufmassRules.normalize(c.text);
    return {
      'label': _label.text.trim(),
      'kind': _kind,
      'length': _dims >= 1 ? n(_length) : null,
      'width': _dims >= 2 ? n(_width) : null,
      'height': _dims >= 3 ? n(_height) : null,
      'count': _kind == 'FORMULA' ? null : n(_count),
      'expression': _kind == 'FORMULA' ? _expression.text.trim() : null,
      'deductions': _hasDeductions
          ? [
              for (final d in _deductions)
                {
                  if (d.label.text.trim().isNotEmpty) 'label': d.label.text.trim(),
                  if (_dims >= 1 && n(d.length) != null) 'length': n(d.length),
                  if (_dims >= 2 && n(d.width) != null) 'width': n(d.width),
                  if (_dims >= 3 && n(d.height) != null) 'height': n(d.height),
                  if (n(d.count) != null) 'count': n(d.count),
                },
            ]
          : <Map<String, dynamic>>[],
      'unitCode': _unit,
    };
  }

  /// The live result, or the error code that keeps it from being calculated.
  (String?, String?) _result() {
    try {
      return (AufmassRules.format(AufmassRules.position(_input(_values()))), null);
    } on FormatException {
      return (null, 'INVALID_NUMBER');
    } on AufmassException catch (e) {
      return (null, e.code);
    }
  }

  Future<void> _save() async {
    final t = AppLocalizations.of(context);
    final (result, code) = _result();
    if (_label.text.trim().isEmpty) {
      setState(() => _error = t.aufmassLabelMissing);
      return;
    }
    if (result == null) {
      setState(() => _error = aufmassError(t, code));
      return;
    }
    final values = _values();
    final writer = ref.read(reportWriterProvider);
    final existing = widget.position;
    if (existing == null) {
      await writer.add(widget.workOrderId, 'aufmass_position', values);
    } else {
      // Only what changed: a field another device changed meanwhile stays theirs.
      final changes = {
        for (final e in values.entries)
          if (jsonEncode(e.value) !=
              jsonEncode(existing.data[e.key] ?? (e.key == 'deductions' ? [] : null)))
            e.key: e.value,
      };
      if (changes.isNotEmpty) await writer.change('aufmass_position', existing.id, changes);
    }
    if (mounted) Navigator.pop(context);
  }

  Widget _number(Key key, TextEditingController c, String label) => Expanded(
    child: TextField(
      key: key,
      controller: c,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
      decoration: InputDecoration(labelText: label, isDense: true),
      onChanged: (_) => setState(() {}),
    ),
  );

  List<Widget> _dimensionFields(
    String prefix,
    TextEditingController l,
    TextEditingController w,
    TextEditingController h,
  ) {
    final t = AppLocalizations.of(context);
    return [
      if (_dims >= 1) _number(Key('$prefix-length'), l, t.lengthLabel),
      if (_dims >= 2) ...[const SizedBox(width: 8), _number(Key('$prefix-width'), w, t.widthLabel)],
      if (_dims >= 3) ...[
        const SizedBox(width: 8),
        _number(Key('$prefix-height'), h, t.heightLabel),
      ],
    ];
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final (result, code) = _result();
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.position == null ? t.addAufmass : t.editAufmass),
        actions: [
          if (widget.position != null)
            IconButton(
              key: const Key('delete-aufmass'),
              tooltip: t.deleteButton,
              icon: const Icon(Icons.delete_outline),
              onPressed: () async {
                await ref
                    .read(reportWriterProvider)
                    .delete('aufmass_position', widget.position!.id);
                if (context.mounted) Navigator.pop(context);
              },
            ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: FilledButton(
            key: const Key('save-aufmass'),
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
            onPressed: _save,
            child: Text(t.saveButton),
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            key: const Key('aufmass-label'),
            controller: _label,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.aufmassLabel),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            key: const Key('aufmass-kind'),
            initialValue: _kind,
            decoration: InputDecoration(labelText: t.aufmassKind),
            items: [
              for (final k in AufmassRules.kinds)
                DropdownMenuItem(value: k, child: Text(aufmassKindLabel(t, k))),
            ],
            onChanged: (k) => setState(() {
              if (k == null) return;
              if (_unit == _defaultUnit[_kind]) _unit = _defaultUnit[k]!;
              _kind = k;
            }),
          ),
          const SizedBox(height: 12),
          if (_kind == 'FORMULA')
            TextField(
              key: const Key('aufmass-expression'),
              controller: _expression,
              keyboardType: TextInputType.visiblePassword,
              decoration: InputDecoration(labelText: t.formulaLabel, helperText: t.formulaHint),
              onChanged: (_) => setState(() {}),
            )
          else
            Row(
              children: [
                ..._dimensionFields('aufmass', _length, _width, _height),
                if (_dims > 0) const SizedBox(width: 8),
                _number(const Key('aufmass-count'), _count, t.countLabel),
              ],
            ),
          if (_hasDeductions) ...[
            const SizedBox(height: 16),
            Text(t.deductionsLabel, style: Theme.of(context).textTheme.titleSmall),
            for (final (i, d) in _deductions.indexed)
              Padding(
                key: Key('deduction-$i'),
                padding: const EdgeInsets.only(top: 8),
                child: Column(
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: d.label,
                            decoration: InputDecoration(labelText: t.deductionLabel, isDense: true),
                          ),
                        ),
                        IconButton(
                          tooltip: t.deleteButton,
                          icon: const Icon(Icons.remove_circle_outline),
                          onPressed: () => setState(() => _deductions.removeAt(i).dispose()),
                        ),
                      ],
                    ),
                    Row(
                      children: [
                        ..._dimensionFields('deduction-$i', d.length, d.width, d.height),
                        const SizedBox(width: 8),
                        _number(Key('deduction-$i-count'), d.count, t.countLabel),
                      ],
                    ),
                  ],
                ),
              ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                key: const Key('add-deduction'),
                style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
                icon: const Icon(Icons.add),
                label: Text(t.addDeduction),
                onPressed: () => setState(() => _deductions.add(_DeductionFields())),
              ),
            ),
          ],
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            key: const Key('aufmass-unit'),
            initialValue: unitCodes.contains(_unit) ? _unit : null,
            decoration: InputDecoration(labelText: t.unitLabel),
            items: [
              for (final u in unitCodes) DropdownMenuItem(value: u, child: Text(unitLabel(t, u))),
            ],
            onChanged: (u) => setState(() => _unit = u ?? _unit),
          ),
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              title: Text(t.resultLabel),
              trailing: Text(
                result == null ? '–' : '${localDecimal(t, result)} ${unitLabel(t, _unit)}',
                key: const Key('aufmass-live-result'),
                style: Theme.of(context).textTheme.titleLarge,
              ),
              subtitle: result == null && code != 'MISSING_DIMENSION' && code != 'INVALID_FORMULA'
                  ? Text(aufmassError(t, code))
                  : null,
            ),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                _error!,
                key: const Key('aufmass-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
    );
  }
}

String aufmassKindLabel(AppLocalizations t, String kind) => switch (kind) {
  'LENGTH' => t.kindLength,
  'AREA' => t.kindArea,
  'VOLUME' => t.kindVolume,
  'PIECE' => t.kindPiece,
  _ => t.kindFormula,
};

String aufmassError(AppLocalizations t, String? code) => switch (code) {
  'MISSING_DIMENSION' => t.aufmassMissingDimension,
  'NEGATIVE_DIMENSION' => t.aufmassNegative,
  'NEGATIVE_RESULT' => t.aufmassNegativeResult,
  'DIVISION_BY_ZERO' => t.aufmassDivisionByZero,
  'INVALID_NUMBER' => t.aufmassInvalidNumber,
  _ => t.aufmassInvalidFormula,
};
