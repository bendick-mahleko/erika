import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../data/local/database.dart';
import '../../data/sync/sync_providers.dart';
import '../../l10n/gen/app_localizations.dart';
import 'work_order_list_screen.dart';
import 'work_order_providers.dart';

/// The conflict inbox (ADR 0038): changes the server decided differently or refused.
class InboxScreen extends ConsumerStatefulWidget {
  const InboxScreen({super.key});

  @override
  ConsumerState<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends ConsumerState<InboxScreen> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() async => (await ref.read(syncStoreProvider.future))?.markInboxSeen());
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final entries = ref.watch(inboxProvider).value ?? const [];
    return Scaffold(
      appBar: AppBar(title: Text(t.inboxTitle)),
      body: entries.isEmpty
          ? Padding(padding: const EdgeInsets.all(16), child: Text(t.inboxEmpty))
          : ListView.separated(
              itemCount: entries.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) => ListTile(
                minVerticalPadding: 12,
                title: Text(inboxText(t, entries[i])),
                subtitle: Text(DateFormat.yMd(t.localeName).add_Hm().format(entries[i].createdAt)),
              ),
            ),
    );
  }
}

String _value(AppLocalizations t, String? field, String? json) {
  if (json == null) return '–';
  final value = jsonDecode(json);
  if (field == 'status' && value is String) return statusLabel(t, value);
  return '$value';
}

String _fieldName(AppLocalizations t, String? field) => switch (field) {
  'status' => t.statusLabel,
  'workNotes' => t.workNotesLabel,
  'title' => t.titleLabel,
  _ => field ?? '',
};

/// What happened, in one sentence.
String inboxText(AppLocalizations t, InboxEntry e) {
  if (e.kind == 'REJECTED') {
    return switch (e.code) {
      'WORK_ORDER_LOCKED' => t.rejectedLocked,
      'NOT_IN_SCOPE' => t.rejectedNotInScope,
      'NOT_FOUND' => t.rejectedNotFound,
      _ => t.rejectedOther(e.code ?? ''),
    };
  }
  final field = _fieldName(t, e.field);
  return e.winner == 'DEVICE'
      ? t.conflictDeviceWon(
          field,
          _value(t, e.field, e.deviceValue),
          _value(t, e.field, e.serverValue),
        )
      : t.conflictServerWon(
          field,
          _value(t, e.field, e.serverValue),
          _value(t, e.field, e.deviceValue),
        );
}
