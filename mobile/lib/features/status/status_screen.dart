import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../app/version.dart';
import '../../data/api/status_api.dart';
import '../../data/session/session.dart';
import '../auth/widgets.dart';
import '../../l10n/gen/app_localizations.dart';
import 'status_providers.dart';

/// Shown when unlocked: who is signed in, that the app reaches the server and
/// that the local database is encrypted. Replaced by the job list in Stage 4.
class StatusScreen extends ConsumerWidget {
  const StatusScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final status = ref.watch(mobileStatusProvider);
    final database = ref.watch(localDatabaseInfoProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.appTitle),
        actions: [
          IconButton(
            key: const Key('lock'),
            tooltip: t.lockNow,
            icon: const Icon(Icons.lock_outline),
            onPressed: () => ref.read(sessionProvider.notifier).lock(),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(mobileStatusProvider);
          try {
            await ref.read(mobileStatusProvider.future);
          } on Object {
            // The error is shown in the server section.
          }
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const _AccountSection(),
            _Section(
              title: t.serverSection,
              child: status.when(
                loading: () => _Loading(label: t.loading),
                error: (_, _) => _ServerError(
                  message: t.serverUnreachable,
                  retryLabel: t.retry,
                  onRetry: () => ref.invalidate(mobileStatusProvider),
                ),
                data: (s) => _ServerStatus(status: s),
              ),
            ),
            const SizedBox(height: 16),
            _Section(
              title: t.databaseSection,
              child: database.when(
                loading: () => _Loading(label: t.loading),
                error: (e, _) => _Line(
                  label: t.databaseError,
                  value: e.runtimeType.toString(),
                  key: const Key('database-error'),
                ),
                data: (info) => Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _Line(label: t.databaseEncrypted, value: info.cipherVersion),
                    _Line(
                      label: t.databaseOpenTime,
                      value: t.milliseconds(info.openTime.inMilliseconds),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            Text(t.appVersionLabel(appVersion), style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}

class _ServerStatus extends StatelessWidget {
  const _ServerStatus({required this.status});

  final MobileStatus status;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final skew = status.serverTime.difference(DateTime.now()).inSeconds.abs();
    final time = DateFormat.yMd(t.localeName).add_Hms().format(status.serverTime.toLocal());
    return Column(
      key: const Key('server-status'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Line(
          label: t.protocolLabel,
          value: status.supports(syncProtocolVersion)
              ? t.protocolSupported(syncProtocolVersion)
              : t.protocolUnsupported(syncProtocolVersion),
        ),
        _Line(
          label: t.updateLabel,
          value: switch (status.appUpdate) {
            AppUpdate.none => t.updateNone,
            AppUpdate.recommended => t.updateRecommended,
            AppUpdate.required => t.updateRequired,
          },
        ),
        _Line(label: t.serverTimeLabel, value: time),
        if (skew > 120) _Line(label: t.clockSkewLabel, value: t.seconds(skew)),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            header: true,
            child: Text(title, style: Theme.of(context).textTheme.titleMedium),
          ),
          const SizedBox(height: 8),
          child,
        ],
      ),
    ),
  );
}

class _Line extends StatelessWidget {
  const _Line({required this.label, required this.value, super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: Text(label)),
        Expanded(child: Text(value, textAlign: TextAlign.end)),
      ],
    ),
  );
}

class _Loading extends StatelessWidget {
  const _Loading({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2)),
      const SizedBox(width: 12),
      Text(label),
    ],
  );
}

class _ServerError extends StatelessWidget {
  const _ServerError({required this.message, required this.retryLabel, required this.onRetry});

  final String message;
  final String retryLabel;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Column(
    key: const Key('server-error'),
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(message),
      const SizedBox(height: 8),
      // Tap targets of at least 48 dp (M-NF-05).
      FilledButton(
        onPressed: onRetry,
        style: FilledButton.styleFrom(minimumSize: const Size(120, 48)),
        child: Text(retryLabel),
      ),
    ],
  );
}

/// Who is signed in on this phone, and the sign-out (deletes the local data).
class _AccountSection extends ConsumerWidget {
  const _AccountSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final session = ref.watch(sessionProvider).value;
    if (session is! Unlocked) return const SizedBox.shrink();
    final user = session.user;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: _Section(
        title: t.accountSection,
        child: Column(
          key: const Key('account'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(user.displayName, style: Theme.of(context).textTheme.titleMedium),
            Text(user.workspaceName),
            Text(switch (user.role) {
              'OWNER' => t.roleOWNER,
              'MEMBER' => t.roleMEMBER,
              _ => t.roleTECHNICIAN,
            }),
            const SizedBox(height: 8),
            OutlinedButton(
              key: const Key('sign-out'),
              style: OutlinedButton.styleFrom(minimumSize: const Size(120, 48)),
              onPressed: () async {
                final confirmed = await confirmSignOut(
                  context,
                  title: t.signOutConfirmTitle,
                  body: t.signOutConfirmBody,
                  confirm: t.signOutButton,
                  cancel: t.cancel,
                );
                if (confirmed) await ref.read(sessionProvider.notifier).signOut();
              },
              child: Text(t.signOutButton),
            ),
          ],
        ),
      ),
    );
  }
}
