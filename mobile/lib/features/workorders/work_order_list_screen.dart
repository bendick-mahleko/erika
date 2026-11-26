import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../data/session/session.dart';
import '../../data/sync/sync_providers.dart';
import '../../l10n/gen/app_localizations.dart';
import '../status/status_screen.dart';
import 'inbox_screen.dart';
import 'new_work_order_screen.dart';
import 'work_order_detail_screen.dart';
import 'work_order_providers.dart';

/// Today's and upcoming work orders (M-MO-01); works offline.
class WorkOrderListScreen extends ConsumerWidget {
  const WorkOrderListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final orders = ref.watch(workOrdersProvider);
    final sync = ref.watch(syncCoordinatorProvider);
    final pending = ref.watch(pendingCountProvider).value ?? 0;
    final unseen = (ref.watch(inboxProvider).value ?? const []).where((e) => !e.seen).length;
    return Scaffold(
      appBar: AppBar(
        title: Text(t.workOrdersTitle),
        actions: [
          IconButton(
            key: const Key('inbox'),
            tooltip: t.inboxTitle,
            icon: Badge(
              isLabelVisible: unseen > 0,
              label: Text('$unseen'),
              child: const Icon(Icons.inbox_outlined),
            ),
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute<void>(builder: (_) => const InboxScreen())),
          ),
          IconButton(
            key: const Key('sync'),
            tooltip: t.syncNow,
            icon: sync.running
                ? const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.sync),
            onPressed: sync.running
                ? null
                : () => ref.read(syncCoordinatorProvider.notifier).syncNow(),
          ),
          PopupMenuButton<String>(
            onSelected: (value) {
              if (value == 'info') {
                Navigator.of(
                  context,
                ).push(MaterialPageRoute<void>(builder: (_) => const StatusScreen()));
              } else if (value == 'lock') {
                ref.read(sessionProvider.notifier).lock();
              } else if (value == 'contrast') {
                ref.read(highContrastProvider.notifier).toggle();
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(value: 'info', child: Text(t.infoMenu)),
              CheckedPopupMenuItem(
                value: 'contrast',
                checked: ref.read(highContrastProvider),
                child: Text(t.highContrast),
              ),
              PopupMenuItem(value: 'lock', child: Text(t.lockNow)),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('new-work-order'),
        icon: const Icon(Icons.add),
        label: Text(t.newWorkOrder),
        onPressed: () => Navigator.of(
          context,
        ).push(MaterialPageRoute<void>(builder: (_) => const NewWorkOrderScreen())),
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.read(syncCoordinatorProvider.notifier).syncNow(),
        child: orders.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => ListView(
            children: [Padding(padding: const EdgeInsets.all(16), child: Text(t.errorGeneric))],
          ),
          data: (list) => ListView(
            padding: const EdgeInsets.only(bottom: 96),
            children: [
              _SyncBanner(status: sync, pending: pending),
              ..._sections(context, t, list),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _sections(BuildContext context, AppLocalizations t, List<WorkOrderView> list) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final tomorrow = today.add(const Duration(days: 1));
    final todays = <WorkOrderView>[];
    final upcoming = <WorkOrderView>[];
    final unplanned = <WorkOrderView>[];
    final earlier = <WorkOrderView>[];
    for (final w in list) {
      final from = w.plannedFrom;
      if (from == null) {
        unplanned.add(w);
      } else if (from.isBefore(today)) {
        earlier.add(w);
      } else if (from.isBefore(tomorrow)) {
        todays.add(w);
      } else {
        upcoming.add(w);
      }
    }
    int byTime(WorkOrderView a, WorkOrderView b) =>
        (a.plannedFrom ?? DateTime(0)).compareTo(b.plannedFrom ?? DateTime(0));
    todays.sort(byTime);
    upcoming.sort(byTime);
    earlier.sort((a, b) => byTime(b, a));
    unplanned.sort((a, b) => a.title.compareTo(b.title));
    if (list.isEmpty) {
      return [
        Padding(
          padding: const EdgeInsets.all(24),
          child: Text(t.noWorkOrders, key: const Key('no-work-orders')),
        ),
      ];
    }
    return [
      if (todays.isNotEmpty) ..._section(context, t.todaySection, todays),
      if (upcoming.isNotEmpty) ..._section(context, t.upcomingSection, upcoming),
      if (unplanned.isNotEmpty) ..._section(context, t.unplannedSection, unplanned),
      if (earlier.isNotEmpty) ..._section(context, t.earlierSection, earlier),
    ];
  }

  List<Widget> _section(BuildContext context, String title, List<WorkOrderView> items) => [
    Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Semantics(
        header: true,
        child: Text(title, style: Theme.of(context).textTheme.titleSmall),
      ),
    ),
    for (final w in items) _WorkOrderTile(order: w),
  ];
}

class _WorkOrderTile extends StatelessWidget {
  const _WorkOrderTile({required this.order});

  final WorkOrderView order;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final from = order.plannedFrom;
    final when = from == null ? null : DateFormat.MMMEd(t.localeName).add_Hm().format(from);
    return ListTile(
      key: Key('work-order-${order.id}'),
      minVerticalPadding: 12,
      title: Text(order.title),
      subtitle: Text(
        [
          when,
          order.customerName,
          order.address,
        ].whereType<String>().where((s) => s.isNotEmpty).join('\n'),
      ),
      isThreeLine: true,
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(statusLabel(t, order.status), style: Theme.of(context).textTheme.labelMedium),
          if (order.pending)
            Icon(Icons.cloud_upload_outlined, size: 18, semanticLabel: t.notYetSent),
        ],
      ),
      onTap: () => Navigator.of(
        context,
      ).push(MaterialPageRoute<void>(builder: (_) => WorkOrderDetailScreen(workOrderId: order.id))),
    );
  }
}

class _SyncBanner extends StatelessWidget {
  const _SyncBanner({required this.status, required this.pending});

  final SyncStatus status;
  final int pending;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final last = status.lastSuccess;
    final lines = [
      if (status.offline) t.syncOffline,
      if (pending > 0) t.syncPending(pending),
      if (last != null) t.syncLast(DateFormat.Hm(t.localeName).format(last)),
    ];
    if (lines.isEmpty) return const SizedBox.shrink();
    return Container(
      key: const Key('sync-banner'),
      width: double.infinity,
      color: status.offline ? Theme.of(context).colorScheme.secondaryContainer : null,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Text(lines.join(' · '), style: Theme.of(context).textTheme.bodySmall),
    );
  }
}

String statusLabel(AppLocalizations t, String status) => switch (status) {
  'PLANNED' => t.statusPlanned,
  'IN_PROGRESS' => t.statusInProgress,
  'DONE' => t.statusDone,
  'SIGNED' => t.statusSigned,
  'INVOICED' => t.statusInvoiced,
  'CANCELLED' => t.statusCancelled,
  _ => status,
};
