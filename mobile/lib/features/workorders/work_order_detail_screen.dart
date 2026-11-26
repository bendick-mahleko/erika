import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../data/sync/sync_providers.dart';
import '../../l10n/gen/app_localizations.dart';
import 'work_order_list_screen.dart';
import 'work_order_providers.dart';

/// Opens [uri] outside the app (maps, phone); overridable in tests.
final externalLauncherProvider = Provider<Future<bool> Function(Uri)>(
  (ref) =>
      (uri) => launchUrl(uri, mode: LaunchMode.externalApplication),
);

/// One work order: status, work notes, route to the site and the customer's phone (M-MO-01).
class WorkOrderDetailScreen extends ConsumerStatefulWidget {
  const WorkOrderDetailScreen({required this.workOrderId, super.key});

  final String workOrderId;

  @override
  ConsumerState<WorkOrderDetailScreen> createState() => _WorkOrderDetailScreenState();
}

class _WorkOrderDetailScreenState extends ConsumerState<WorkOrderDetailScreen> {
  final _notes = TextEditingController();
  var _notesLoaded = false;

  @override
  void dispose() {
    _notes.dispose();
    super.dispose();
  }

  Future<void> _change(Map<String, dynamic> values) async {
    final store = await ref.read(syncStoreProvider.future);
    if (store == null) return;
    await store.change('work_order', widget.workOrderId, values);
    ref.read(syncCoordinatorProvider.notifier).changed();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final orders = ref.watch(workOrdersProvider).value ?? const [];
    final order = orders.where((o) => o.id == widget.workOrderId).firstOrNull;
    if (order == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Padding(padding: const EdgeInsets.all(16), child: Text(t.workOrderGone)),
      );
    }
    if (!_notesLoaded) {
      _notes.text = order.workNotes ?? '';
      _notesLoaded = true;
    }
    final launcher = ref.read(externalLauncherProvider);
    final from = order.plannedFrom;
    final to = order.plannedTo;
    final time = from == null
        ? t.noDate
        : '${DateFormat.yMMMEd(t.localeName).add_Hm().format(from)}${to == null ? '' : ' – ${DateFormat.Hm(t.localeName).format(to)}'}';
    return Scaffold(
      appBar: AppBar(title: Text(order.title)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (order.pending)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                t.notYetSent,
                key: const Key('pending'),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          Text(time),
          const SizedBox(height: 8),
          Text(order.customerName, style: Theme.of(context).textTheme.titleMedium),
          if (order.address != null) Text(order.address!),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              if (order.address != null)
                OutlinedButton.icon(
                  key: const Key('route'),
                  style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                  icon: const Icon(Icons.directions),
                  label: Text(t.route),
                  onPressed: () => launcher(mapsUri(order.address!)),
                ),
              if (order.phone != null && order.phone!.isNotEmpty)
                OutlinedButton.icon(
                  key: const Key('call'),
                  style: OutlinedButton.styleFrom(minimumSize: const Size(48, 48)),
                  icon: const Icon(Icons.phone),
                  label: Text(t.callCustomer),
                  onPressed: () =>
                      launcher(Uri(scheme: 'tel', path: order.phone!.replaceAll(' ', ''))),
                ),
            ],
          ),
          if (order.description != null && order.description!.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(order.description!),
          ],
          const SizedBox(height: 24),
          Semantics(
            header: true,
            child: Text(t.statusLabel, style: Theme.of(context).textTheme.titleSmall),
          ),
          const SizedBox(height: 8),
          if (order.locked)
            Text(statusLabel(t, order.status))
          else
            SegmentedButton<String>(
              key: const Key('status'),
              segments: [
                for (final s in const ['PLANNED', 'IN_PROGRESS', 'DONE'])
                  ButtonSegment(value: s, label: Text(statusLabel(t, s))),
              ],
              selected: {
                if (const ['PLANNED', 'IN_PROGRESS', 'DONE'].contains(order.status)) order.status,
              },
              emptySelectionAllowed: true,
              onSelectionChanged: (selected) {
                if (selected.isNotEmpty) _change({'status': selected.first});
              },
            ),
          const SizedBox(height: 24),
          TextField(
            key: const Key('work-notes'),
            controller: _notes,
            enabled: !order.locked,
            minLines: 3,
            maxLines: 8,
            decoration: InputDecoration(
              labelText: t.workNotesLabel,
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              key: const Key('save-notes'),
              style: FilledButton.styleFrom(minimumSize: const Size(120, 48)),
              onPressed: order.locked
                  ? null
                  : () async {
                      await _change({
                        'workNotes': _notes.text.trim().isEmpty ? null : _notes.text.trim(),
                      });
                      if (context.mounted) {
                        ScaffoldMessenger.of(
                          context,
                        ).showSnackBar(SnackBar(content: Text(t.savedLocally)));
                      }
                    },
              child: Text(t.saveButton),
            ),
          ),
        ],
      ),
    );
  }
}

/// The site in the phone's maps app.
Uri mapsUri(String address) => Platform.isIOS
    ? Uri.https('maps.apple.com', '/', {'q': address})
    : Uri(scheme: 'geo', path: '0,0', queryParameters: {'q': address});
