import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/database.dart';
import '../../data/sync/sync_providers.dart';
import '../../data/sync/sync_store.dart';

/// A work order with its customer and site, as the screens show it.
class WorkOrderView {
  const WorkOrderView({required this.record, this.customer, this.site});

  final LocalRecord record;
  final LocalRecord? customer;
  final LocalRecord? site;

  String get id => record.id;
  String get title => record.text('title') ?? '';
  String get status => record.text('status') ?? 'PLANNED';
  String? get description => record.text('description');
  String? get workNotes => record.text('workNotes');
  DateTime? get plannedFrom => _time(record.text('plannedFrom'));
  DateTime? get plannedTo => _time(record.text('plannedTo'));
  String get customerName => customer?.text('name') ?? '';
  String? get phone => customer?.text('phone');
  bool get pending => record.pending;

  /// Signed or invoiced work orders don't change any more.
  bool get locked => status == 'SIGNED' || status == 'INVOICED';

  /// Where the work is: the site, else the customer's address.
  String? get address {
    final source = site ?? customer;
    if (source == null) return null;
    final street = source.text('street');
    final city = [source.text('postalCode'), source.text('city')].whereType<String>().join(' ');
    final parts = [
      street,
      if (city.isNotEmpty) city,
    ].whereType<String>().where((p) => p.isNotEmpty);
    return parts.isEmpty ? null : parts.join(', ');
  }

  static DateTime? _time(String? value) =>
      value == null ? null : DateTime.tryParse(value)?.toLocal();
}

Stream<List<LocalRecord>> _watch(Ref ref, String entity) async* {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) {
    yield const [];
    return;
  }
  yield* store.watch(entity);
}

final workOrderRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'work_order'),
);
final customerRecordsProvider = StreamProvider<List<LocalRecord>>((ref) => _watch(ref, 'customer'));
final siteRecordsProvider = StreamProvider<List<LocalRecord>>((ref) => _watch(ref, 'site'));

final workOrdersProvider = Provider<AsyncValue<List<WorkOrderView>>>((ref) {
  final orders = ref.watch(workOrderRecordsProvider);
  final customers = {
    for (final c in ref.watch(customerRecordsProvider).value ?? const <LocalRecord>[]) c.id: c,
  };
  final sites = {
    for (final s in ref.watch(siteRecordsProvider).value ?? const <LocalRecord>[]) s.id: s,
  };
  return orders.whenData(
    (list) => [
      for (final o in list)
        WorkOrderView(
          record: o,
          customer: customers[o.text('customerId')],
          site: sites[o.text('siteId')],
        ),
    ],
  );
});

/// Active customers, by name (for a new work order on site).
final activeCustomersProvider = Provider<List<LocalRecord>>((ref) {
  final list = [...?ref.watch(customerRecordsProvider).value]
    ..removeWhere((c) => c.data['archived'] == true)
    ..sort(
      (a, b) =>
          (a.text('name') ?? '').toLowerCase().compareTo((b.text('name') ?? '').toLowerCase()),
    );
  return list;
});

final pendingCountProvider = StreamProvider<int>((ref) async* {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) {
    yield 0;
    return;
  }
  yield* store.watchPendingCount();
});

final inboxProvider = StreamProvider<List<InboxEntry>>((ref) async* {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) {
    yield const [];
    return;
  }
  yield* store.watchInbox();
});
