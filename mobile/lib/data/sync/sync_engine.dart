import 'dart:async';

import '../api/auth_api.dart';
import 'sync_api.dart';
import 'sync_store.dart';

enum SyncOutcome {
  /// Outbox empty and every change pulled.
  done,

  /// No connection (or the server is unreachable): try again later; nothing is lost.
  offline,

  /// Something else went wrong (e.g. sign-in needed); the session handles it.
  failed,
}

/// Runs a sync with a valid access token (renews it once when needed; reacts to revocation).
typedef Authorized = Future<T> Function<T>(Future<T> Function(String accessToken) call);

/// One sync run (ADR 0038): first the outbox in batches of up to 200, oldest first, then pull
/// until there is nothing more. Only one run at a time; a request while one runs joins it.
/// A run that dies half-way (no network, app killed) leaves the outbox and the cursor as they
/// were, so the next run resends and the server answers resends from its idempotency log.
class SyncEngine {
  SyncEngine({required this.store, required this.transport, required this.authorized});

  final SyncStore store;
  final SyncTransport transport;
  final Authorized authorized;

  Future<SyncOutcome>? _running;

  Future<SyncOutcome> sync() => _running ??= _run().whenComplete(() => _running = null);

  Future<SyncOutcome> _run() async {
    try {
      while (true) {
        final batch = await store.pending();
        if (batch.isEmpty) break;
        final results = await authorized((token) => transport.push(token, batch));
        await store.applyPushResults(results);
      }
      while (true) {
        final cursor = await store.cursor();
        final page = await authorized((token) => transport.pull(token, cursor));
        await store.applyPull(page);
        if (!page.hasMore) break;
      }
      await store.db.putSetting(SyncStore.lastSyncKey, DateTime.now().toUtc().toIso8601String());
      return SyncOutcome.done;
    } on ApiProblem catch (problem) {
      return problem.isNetwork ? SyncOutcome.offline : SyncOutcome.failed;
    }
  }
}
