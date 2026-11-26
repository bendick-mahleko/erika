import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/sync/sync_store.dart';
import '../../domain/rules/aufmass.dart';
import '../../l10n/gen/app_localizations.dart';
import 'report_providers.dart';
import 'report_screen.dart';

/// Adds material (M-MO-04): recent items first, then the article list with search, or free
/// text. From "recent items" it takes 3 taps: "Add material", the item, "Add".
Future<void> showMaterialPicker(
  BuildContext context,
  WidgetRef ref, {
  required String workOrderId,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (_) => _MaterialPicker(workOrderId: workOrderId),
);

class _MaterialPicker extends ConsumerStatefulWidget {
  const _MaterialPicker({required this.workOrderId});

  final String workOrderId;

  @override
  ConsumerState<_MaterialPicker> createState() => _MaterialPickerState();
}

class _MaterialPickerState extends ConsumerState<_MaterialPicker> {
  final _search = TextEditingController();
  var _query = '';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _pick({
    required String description,
    required String unitCode,
    String? articleId,
  }) async {
    final quantity = await showDialog<String>(
      context: context,
      builder: (_) => _QuantityDialog(description: description, unitCode: unitCode),
    );
    if (quantity == null) return;
    await ref.read(reportWriterProvider).add(widget.workOrderId, 'material_line', {
      'articleId': articleId,
      'description': description,
      'quantity': quantity,
      'unitCode': unitCode,
    });
    if (mounted) Navigator.pop(context);
  }

  Future<void> _freeText() async {
    final result = await showDialog<(String, String, String)>(
      context: context,
      builder: (_) => _FreeTextDialog(initial: _query),
    );
    if (result == null) return;
    final (description, quantity, unit) = result;
    await ref.read(reportWriterProvider).add(widget.workOrderId, 'material_line', {
      'articleId': null,
      'description': description,
      'quantity': quantity,
      'unitCode': unit,
    });
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final q = _query.trim().toLowerCase();
    final recent = q.isEmpty ? ref.watch(recentMaterialsProvider) : const <RecentMaterial>[];
    final articles = ref
        .watch(activeArticlesProvider)
        .where(
          (a) =>
              q.isEmpty ||
              (a.text('name') ?? '').toLowerCase().contains(q) ||
              (a.text('articleNumber') ?? '').toLowerCase().contains(q),
        )
        .take(100)
        .toList();
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.9,
      builder: (context, scroll) => ListView(
        key: const Key('material-picker'),
        controller: scroll,
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            key: const Key('material-search'),
            controller: _search,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              labelText: t.searchArticles,
              border: const OutlineInputBorder(),
            ),
            onChanged: (v) => setState(() => _query = v),
          ),
          const SizedBox(height: 8),
          ListTile(
            key: const Key('material-free-text'),
            minTileHeight: 56,
            leading: const Icon(Icons.edit_note),
            title: Text(q.isEmpty ? t.freeTextMaterial : t.freeTextMaterialNamed(_query.trim())),
            onTap: _freeText,
          ),
          if (recent.isNotEmpty) ...[
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(t.recentMaterials, style: Theme.of(context).textTheme.titleSmall),
            ),
            for (final (i, r) in recent.indexed)
              ListTile(
                key: Key('recent-$i'),
                minTileHeight: 56,
                leading: const Icon(Icons.history),
                title: Text(r.description),
                subtitle: r.articleNumber == null ? null : Text(r.articleNumber!),
                trailing: Text(unitLabel(t, r.unitCode)),
                onTap: () =>
                    _pick(description: r.description, unitCode: r.unitCode, articleId: r.articleId),
              ),
          ],
          if (articles.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(t.articleList, style: Theme.of(context).textTheme.titleSmall),
            ),
          for (final a in articles)
            ListTile(
              key: Key('article-${a.id}'),
              minTileHeight: 56,
              title: Text(a.text('name') ?? ''),
              subtitle: a.text('articleNumber') == null ? null : Text(a.text('articleNumber')!),
              trailing: Text(unitLabel(t, a.text('unitCode') ?? 'C62')),
              onTap: () => _pick(
                description: a.text('name') ?? '',
                unitCode: a.text('unitCode') ?? 'C62',
                articleId: a.id,
              ),
            ),
        ],
      ),
    );
  }
}

/// Asks for the quantity; 1 is preset, so "Add" right away is the third tap.
class _QuantityDialog extends StatefulWidget {
  const _QuantityDialog({required this.description, required this.unitCode});

  final String description;
  final String unitCode;

  @override
  State<_QuantityDialog> createState() => _QuantityDialogState();
}

class _QuantityDialogState extends State<_QuantityDialog> {
  final _quantity = TextEditingController(text: '1');
  String? _error;

  @override
  void dispose() {
    _quantity.dispose();
    super.dispose();
  }

  void _submit() {
    final value = validQuantity(_quantity.text);
    if (value == null) {
      setState(() => _error = AppLocalizations.of(context).invalidQuantity);
      return;
    }
    Navigator.pop(context, value);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return AlertDialog(
      key: const Key('quantity-dialog'),
      title: Text(widget.description),
      content: TextField(
        key: const Key('quantity'),
        controller: _quantity,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: InputDecoration(
          labelText: t.quantityLabel,
          suffixText: unitLabel(t, widget.unitCode),
          errorText: _error,
        ),
        onSubmitted: (_) => _submit(),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(key: const Key('add-quantity'), onPressed: _submit, child: Text(t.addButton)),
      ],
    );
  }
}

/// A quantity as the server expects it ("2.5"), or null when it isn't a positive number.
String? validQuantity(String text) {
  try {
    final value = AufmassRules.normalize(text);
    if (value == null || double.parse(value) <= 0) return null;
    return value;
  } on FormatException {
    return null;
  }
}

class _FreeTextDialog extends StatefulWidget {
  const _FreeTextDialog({required this.initial});

  final String initial;

  @override
  State<_FreeTextDialog> createState() => _FreeTextDialogState();
}

class _FreeTextDialogState extends State<_FreeTextDialog> {
  late final _description = TextEditingController(text: widget.initial.trim());
  final _quantity = TextEditingController(text: '1');
  var _unit = 'C62';
  String? _error;

  @override
  void dispose() {
    _description.dispose();
    _quantity.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return AlertDialog(
      key: const Key('free-text-dialog'),
      title: Text(t.freeTextMaterial),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('free-text-description'),
            controller: _description,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.descriptionLabel),
          ),
          TextField(
            key: const Key('free-text-quantity'),
            controller: _quantity,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: t.quantityLabel),
          ),
          DropdownButtonFormField<String>(
            key: const Key('free-text-unit'),
            initialValue: _unit,
            decoration: InputDecoration(labelText: t.unitLabel),
            items: [
              for (final u in unitCodes) DropdownMenuItem(value: u, child: Text(unitLabel(t, u))),
            ],
            onChanged: (v) => setState(() => _unit = v ?? 'C62'),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(
          key: const Key('add-free-text'),
          onPressed: () {
            final quantity = validQuantity(_quantity.text);
            if (_description.text.trim().isEmpty || quantity == null) {
              setState(() => _error = t.materialIncomplete);
              return;
            }
            Navigator.pop(context, (_description.text.trim(), quantity, _unit));
          },
          child: Text(t.addButton),
        ),
      ],
    );
  }
}

/// Changes the quantity, text or unit of a material line, or deletes it.
Future<void> showMaterialEditDialog(BuildContext context, WidgetRef ref, LocalRecord line) =>
    showDialog<void>(
      context: context,
      builder: (_) => _MaterialEditDialog(line: line),
    );

class _MaterialEditDialog extends ConsumerStatefulWidget {
  const _MaterialEditDialog({required this.line});

  final LocalRecord line;

  @override
  ConsumerState<_MaterialEditDialog> createState() => _MaterialEditDialogState();
}

class _MaterialEditDialogState extends ConsumerState<_MaterialEditDialog> {
  late final _description = TextEditingController(text: widget.line.text('description') ?? '');
  final _quantity = TextEditingController();
  late var _unit = widget.line.text('unitCode') ?? 'C62';
  String? _error;
  var _filled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_filled) return;
    _filled = true;
    _quantity.text = localDecimal(AppLocalizations.of(context), widget.line.text('quantity'));
  }

  @override
  void dispose() {
    _description.dispose();
    _quantity.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final writer = ref.read(reportWriterProvider);
    return AlertDialog(
      key: const Key('material-edit-dialog'),
      title: Text(t.editMaterial),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('edit-material-description'),
            controller: _description,
            decoration: InputDecoration(labelText: t.descriptionLabel),
          ),
          TextField(
            key: const Key('edit-material-quantity'),
            controller: _quantity,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: t.quantityLabel),
          ),
          DropdownButtonFormField<String>(
            initialValue: unitCodes.contains(_unit) ? _unit : null,
            decoration: InputDecoration(labelText: t.unitLabel),
            items: [
              for (final u in unitCodes) DropdownMenuItem(value: u, child: Text(unitLabel(t, u))),
            ],
            onChanged: (v) => setState(() => _unit = v ?? _unit),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
        ],
      ),
      actions: [
        TextButton(
          key: const Key('delete-material'),
          onPressed: () async {
            await writer.delete('material_line', widget.line.id);
            if (context.mounted) Navigator.pop(context);
          },
          child: Text(t.deleteButton),
        ),
        TextButton(onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(
          key: const Key('save-material'),
          onPressed: () async {
            final quantity = validQuantity(_quantity.text);
            if (_description.text.trim().isEmpty || quantity == null) {
              setState(() => _error = t.materialIncomplete);
              return;
            }
            final values = <String, dynamic>{
              if (_description.text.trim() != widget.line.text('description'))
                'description': _description.text.trim(),
              if (quantity != widget.line.text('quantity')) 'quantity': quantity,
              if (_unit != widget.line.text('unitCode')) 'unitCode': _unit,
            };
            if (values.isNotEmpty) await writer.change('material_line', widget.line.id, values);
            if (context.mounted) Navigator.pop(context);
          },
          child: Text(t.saveButton),
        ),
      ],
    );
  }
}
