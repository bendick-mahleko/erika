import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../data/sync/sync_providers.dart';
import '../../l10n/gen/app_localizations.dart';
import '../auth/widgets.dart';
import 'work_order_providers.dart';

/// A work order made on site, offline if need be, for an existing or a new customer (M-MO-02).
/// The office is told when it reaches the server.
class NewWorkOrderScreen extends ConsumerStatefulWidget {
  const NewWorkOrderScreen({super.key});

  @override
  ConsumerState<NewWorkOrderScreen> createState() => _NewWorkOrderScreenState();
}

class _NewWorkOrderScreenState extends ConsumerState<NewWorkOrderScreen> {
  final _title = TextEditingController();
  final _description = TextEditingController();
  final _name = TextEditingController();
  final _street = TextEditingController();
  final _postalCode = TextEditingController();
  final _city = TextEditingController();
  final _phone = TextEditingController();
  String? _customerId;
  var _newCustomer = false;
  DateTime? _date;
  TimeOfDay? _from;
  String? _error;
  var _busy = false;

  @override
  void dispose() {
    for (final c in [_title, _description, _name, _street, _postalCode, _city, _phone]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final t = AppLocalizations.of(context);
    final title = _title.text.trim();
    if (title.isEmpty) return setState(() => _error = t.titleRequired);
    if (_newCustomer ? _name.text.trim().isEmpty : _customerId == null) {
      return setState(() => _error = t.customerRequired);
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final store = await ref.read(syncStoreProvider.future);
    if (store == null) return;
    String? clean(TextEditingController c) => c.text.trim().isEmpty ? null : c.text.trim();
    var customerId = _customerId;
    if (_newCustomer) {
      customerId = await store.create('customer', {
        'name': _name.text.trim(),
        'street': clean(_street),
        'postalCode': clean(_postalCode),
        'city': clean(_city),
        'phone': clean(_phone),
      });
    }
    DateTime? from;
    final date = _date;
    if (date != null) {
      final time = _from ?? const TimeOfDay(hour: 8, minute: 0);
      from = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    }
    await store.create('work_order', {
      'customerId': customerId,
      'title': title,
      'description': clean(_description),
      'plannedFrom': from?.toUtc().toIso8601String(),
      'plannedTo': from?.add(const Duration(hours: 1)).toUtc().toIso8601String(),
      'status': 'PLANNED',
    });
    ref.read(syncCoordinatorProvider.notifier).changed();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final customers = ref.watch(activeCustomersProvider);
    InputDecoration field(String label) =>
        InputDecoration(labelText: label, border: const OutlineInputBorder());
    return Scaffold(
      appBar: AppBar(title: Text(t.newWorkOrder)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_error != null) ...[ErrorText(_error!), const SizedBox(height: 12)],
          TextField(key: const Key('title'), controller: _title, decoration: field(t.titleLabel)),
          const SizedBox(height: 12),
          SwitchListTile(
            key: const Key('new-customer'),
            contentPadding: EdgeInsets.zero,
            title: Text(t.newCustomer),
            value: _newCustomer,
            onChanged: (v) => setState(() => _newCustomer = v),
          ),
          if (_newCustomer) ...[
            TextField(
              key: const Key('customer-name'),
              controller: _name,
              decoration: field(t.customerNameLabel),
            ),
            const SizedBox(height: 8),
            TextField(controller: _street, decoration: field(t.streetLabel)),
            const SizedBox(height: 8),
            Row(
              children: [
                SizedBox(
                  width: 110,
                  child: TextField(controller: _postalCode, decoration: field(t.postalCodeLabel)),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(controller: _city, decoration: field(t.cityLabel)),
                ),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _phone,
              keyboardType: TextInputType.phone,
              decoration: field(t.phoneLabel),
            ),
          ] else
            DropdownButtonFormField<String>(
              key: const Key('customer'),
              initialValue: _customerId,
              isExpanded: true,
              decoration: field(t.customerLabel),
              items: [
                for (final c in customers)
                  DropdownMenuItem(
                    value: c.id,
                    child: Text(c.text('name') ?? '', overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: (v) => setState(() => _customerId = v),
            ),
          const SizedBox(height: 12),
          TextField(
            controller: _description,
            minLines: 2,
            maxLines: 5,
            decoration: field(t.descriptionLabel),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            children: [
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                icon: const Icon(Icons.event),
                label: Text(
                  _date == null ? t.pickDate : DateFormat.yMMMd(t.localeName).format(_date!),
                ),
                onPressed: () async {
                  final now = DateTime.now();
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: _date ?? now,
                    firstDate: now.subtract(const Duration(days: 30)),
                    lastDate: now.add(const Duration(days: 365)),
                  );
                  if (picked != null) setState(() => _date = picked);
                },
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                icon: const Icon(Icons.schedule),
                label: Text(_from == null ? t.pickTime : _from!.format(context)),
                onPressed: () async {
                  final picked = await showTimePicker(
                    context: context,
                    initialTime: _from ?? TimeOfDay.now(),
                  );
                  if (picked != null) setState(() => _from = picked);
                },
              ),
            ],
          ),
          const SizedBox(height: 24),
          BigButton(label: t.createWorkOrder, busy: _busy, onPressed: _save),
        ],
      ),
    );
  }
}
