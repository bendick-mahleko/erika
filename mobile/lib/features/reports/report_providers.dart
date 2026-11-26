import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/session/session.dart';
import '../../data/sync/sync_providers.dart';
import '../../data/sync/sync_store.dart';
import '../../domain/derived_id.dart';
import '../workorders/work_order_providers.dart';

/*
 * MARA Stage 5: the job report of a work order on the phone (M-MO-03 to M-MO-08). Everything
 * is written through the SyncStore (record and outbox in one transaction), so it works offline
 * and nothing is lost when the app is killed.
 */

Stream<List<LocalRecord>> _watch(Ref ref, String entity) async* {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) {
    yield const [];
    return;
  }
  yield* store.watch(entity);
}

final reportRecordsProvider = StreamProvider<List<LocalRecord>>((ref) => _watch(ref, 'report'));
final timeEntryRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'time_entry'),
);
final materialRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'material_line'),
);
final aufmassRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'aufmass_position'),
);
final answerRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'checklist_answer'),
);
final articleRecordsProvider = StreamProvider<List<LocalRecord>>((ref) => _watch(ref, 'article'));
final staffRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'staff_member'),
);
final templateRecordsProvider = StreamProvider<List<LocalRecord>>(
  (ref) => _watch(ref, 'checklist_template'),
);

/// The signed-in user's ID (author of new records, default technician of a time entry).
final currentUserIdProvider = Provider<String?>((ref) {
  return switch (ref.watch(sessionProvider).value) {
    Unlocked(:final user) => user.userId,
    ReauthRequired(:final user) => user.userId,
    _ => null,
  };
});

/// Owners and members may change every record of a report; technicians their own (ADR 0038).
final isOfficeUserProvider = Provider<bool>((ref) {
  return switch (ref.watch(sessionProvider).value) {
    Unlocked(:final user) => user.role != 'TECHNICIAN',
    ReauthRequired(:final user) => user.role != 'TECHNICIAN',
    _ => false,
  };
});

/// One checklist item of the work order's template.
class ChecklistItemView {
  const ChecklistItemView({
    required this.key,
    required this.label,
    required this.kind,
    required this.required,
    this.value,
  });

  final String key;
  final String label;

  /// YES_NO, VALUE, PHOTO or COMMENT.
  final String kind;
  final bool required;
  final String? value;

  bool get answered => value != null && value!.trim().isNotEmpty;
}

/// A work order's report with everything recorded for it, live records only.
class ReportView {
  const ReportView({
    required this.workOrder,
    required this.reportId,
    required this.report,
    required this.times,
    required this.materials,
    required this.aufmass,
    required this.checklist,
    required this.staffNames,
  });

  final WorkOrderView workOrder;
  final String reportId;

  /// Null until something was recorded.
  final LocalRecord? report;
  final List<LocalRecord> times;
  final List<LocalRecord> materials;
  final List<LocalRecord> aufmass;
  final List<ChecklistItemView> checklist;
  final Map<String, String> staffNames;

  bool get completed => report?.text('state') == 'COMPLETED';
  String? get workDescription => report?.text('workDescription');
  bool get locked => workOrder.locked;

  List<LocalRecord> get runningTimers => times.where((t) => t.text('endedAt') == null).toList();

  /// Mandatory items still open; photo items follow in Stage 6 and don't block yet (M-MO-07).
  List<ChecklistItemView> get missingItems =>
      checklist.where((i) => i.required && i.kind != 'PHOTO' && !i.answered).toList();

  bool get completable => runningTimers.isEmpty && missingItems.isEmpty;
}

List<LocalRecord> _children(AsyncValue<List<LocalRecord>> records, String reportId) {
  final list = [
    for (final r in records.value ?? const <LocalRecord>[])
      if (r.text('reportId') == reportId && !r.deleted) r,
  ];
  // UUIDv7 IDs sort by creation time.
  list.sort((a, b) => a.id.compareTo(b.id));
  return list;
}

final reportViewProvider = Provider.family<ReportView?, String>((ref, workOrderId) {
  final order = (ref.watch(workOrdersProvider).value ?? const <WorkOrderView>[])
      .where((o) => o.id == workOrderId)
      .firstOrNull;
  if (order == null) return null;
  final reportId = reportIdFor(workOrderId);
  final report = (ref.watch(reportRecordsProvider).value ?? const <LocalRecord>[])
      .where((r) => r.id == reportId)
      .firstOrNull;
  final answers = {
    for (final a in _children(ref.watch(answerRecordsProvider), reportId)) a.text('itemKey'): a,
  };
  final template = (ref.watch(templateRecordsProvider).value ?? const <LocalRecord>[])
      .where((t) => t.id == order.checklistTemplateId)
      .firstOrNull;
  final items = [
    for (final i
        in (template?.data['items'] as List<dynamic>? ?? const []).cast<Map<String, dynamic>>())
      ChecklistItemView(
        key: i['key'] as String,
        label: i['label'] as String,
        kind: i['kind'] as String,
        required: i['required'] == true,
        value: answers[i['key']]?.text('value'),
      ),
  ];
  return ReportView(
    workOrder: order,
    reportId: reportId,
    report: report,
    times: _children(ref.watch(timeEntryRecordsProvider), reportId)
      ..sort((a, b) => (a.text('startedAt') ?? '').compareTo(b.text('startedAt') ?? '')),
    materials: _children(ref.watch(materialRecordsProvider), reportId),
    aufmass: _children(ref.watch(aufmassRecordsProvider), reportId),
    checklist: items,
    staffNames: {
      for (final s in ref.watch(staffRecordsProvider).value ?? const <LocalRecord>[])
        s.id: s.text('displayName') ?? '',
    },
  );
});

/// An article or a free text used before: the "recent items" of the material picker (M-MO-04).
class RecentMaterial {
  const RecentMaterial({
    required this.description,
    required this.unitCode,
    this.articleId,
    this.articleNumber,
  });

  final String? articleId;
  final String? articleNumber;
  final String description;
  final String unitCode;
}

/// The materials this phone recorded most recently, newest first, each article or text once.
final recentMaterialsProvider = Provider<List<RecentMaterial>>((ref) {
  final lines = [...?ref.watch(materialRecordsProvider).value]
    ..sort((a, b) => b.id.compareTo(a.id));
  final articles = {
    for (final a in ref.watch(articleRecordsProvider).value ?? const <LocalRecord>[]) a.id: a,
  };
  final seen = <String>{};
  final recent = <RecentMaterial>[];
  for (final l in lines) {
    final articleId = l.text('articleId');
    final key = articleId ?? 'text:${l.text('description')}';
    if (!seen.add(key)) continue;
    recent.add(
      RecentMaterial(
        articleId: articleId,
        articleNumber: articleId == null ? null : articles[articleId]?.text('articleNumber'),
        description: l.text('description') ?? '',
        unitCode: l.text('unitCode') ?? 'C62',
      ),
    );
    if (recent.length == 8) break;
  }
  return recent;
});

/// Active articles, by name.
final activeArticlesProvider = Provider<List<LocalRecord>>((ref) {
  final list = [...?ref.watch(articleRecordsProvider).value]
    ..removeWhere((a) => a.data['archived'] == true)
    ..sort(
      (a, b) =>
          (a.text('name') ?? '').toLowerCase().compareTo((b.text('name') ?? '').toLowerCase()),
    );
  return list;
});

/// Writes of the report screens: each one is a local transaction plus an outbox entry; the
/// report itself is created on the first write (its ID is derived from the work order).
class ReportWriter {
  ReportWriter(this.ref);

  final Ref ref;

  Future<SyncStore?> _store() => ref.read(syncStoreProvider.future);

  void _changed() => ref.read(syncCoordinatorProvider.notifier).changed();

  Future<String?> ensureReport(String workOrderId) async {
    final store = await _store();
    if (store == null) return null;
    final id = reportIdFor(workOrderId);
    await store.put('report', id, createValues: {'workOrderId': workOrderId});
    return id;
  }

  Future<void> changeReport(String workOrderId, Map<String, dynamic> values) async {
    final store = await _store();
    final id = await ensureReport(workOrderId);
    if (store == null || id == null) return;
    await store.change('report', id, values);
    _changed();
  }

  Future<String?> add(String workOrderId, String entity, Map<String, dynamic> values) async {
    final store = await _store();
    final reportId = await ensureReport(workOrderId);
    if (store == null || reportId == null) return null;
    final id = await store.create(entity, {'reportId': reportId, ...values});
    _changed();
    return id;
  }

  Future<void> change(String entity, String id, Map<String, dynamic> values) async {
    final store = await _store();
    if (store == null) return;
    await store.change(entity, id, values);
    _changed();
  }

  Future<void> delete(String entity, String id) async {
    final store = await _store();
    if (store == null) return;
    await store.delete(entity, id);
    _changed();
  }

  /// Answers a checklist item (the answer's ID is derived from report and item key).
  Future<void> answer(String workOrderId, String itemKey, String? value) async {
    final store = await _store();
    final reportId = await ensureReport(workOrderId);
    if (store == null || reportId == null) return;
    await store.put(
      'checklist_answer',
      checklistAnswerIdFor(reportId, itemKey),
      createValues: {'reportId': reportId, 'itemKey': itemKey},
      changes: {'value': value},
    );
    _changed();
  }
}

final reportWriterProvider = Provider<ReportWriter>(ReportWriter.new);
