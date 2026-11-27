import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/config.dart';
import '../local/database.dart';
import '../media/media_providers.dart';
import '../session/session.dart';
import '../telemetry/telemetry.dart';
import 'sync_api.dart';
import 'sync_engine.dart';
import 'sync_store.dart';

/// The database key while the app is unlocked (or waiting for a new sign-in); null when locked.
final _databaseKeyProvider = Provider<String?>((ref) {
  final session = ref.watch(sessionProvider).value;
  return switch (session) {
    Unlocked(:final databaseKey) => databaseKey,
    ReauthRequired(:final databaseKey) => databaseKey,
    _ => null,
  };
});

/// The open, encrypted database; closed again when the app locks.
final appDatabaseProvider = FutureProvider<AppDatabase?>((ref) async {
  final key = ref.watch(_databaseKeyProvider);
  if (key == null) return null;
  final file = await ref.watch(localFilesProvider).databaseFile();
  final db = AppDatabase.openEncrypted(file, key);
  ref.onDispose(db.close);
  return db;
});

final syncStoreProvider = FutureProvider<SyncStore?>((ref) async {
  final db = await ref.watch(appDatabaseProvider.future);
  return db == null ? null : SyncStore(db);
});

/// Content-free counters for the field test (MARA Stage 9), in the encrypted database.
final telemetryProvider = FutureProvider<Telemetry?>((ref) async {
  final db = await ref.watch(appDatabaseProvider.future);
  return db == null ? null : Telemetry(db, clock: ref.watch(clockProvider));
});

final telemetryTransportProvider = Provider<TelemetryTransport>(
  (ref) => TelemetryApi(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);

final syncTransportProvider = Provider<SyncTransport>(
  (ref) => SyncApi(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);

final syncEngineProvider = FutureProvider<SyncEngine?>((ref) async {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) return null;
  final session = ref.read(sessionProvider.notifier);
  return SyncEngine(
    store: store,
    transport: ref.watch(syncTransportProvider),
    authorized: session.authorized,
    telemetry: await ref.watch(telemetryProvider.future),
  );
});

/// How often the app syncs while it is open (a status change shows within a minute, M-OF-02);
/// null switches the timer off (tests).
final syncIntervalProvider = Provider<Duration?>((ref) => const Duration(seconds: 30));

/// What the screens show about the sync.
class SyncStatus {
  const SyncStatus({this.running = false, this.outcome, this.lastSuccess});

  final bool running;
  final SyncOutcome? outcome;
  final DateTime? lastSuccess;

  bool get offline => outcome == SyncOutcome.offline;
}

final syncCoordinatorProvider = NotifierProvider<SyncCoordinator, SyncStatus>(SyncCoordinator.new);

/// When to sync (ADR 0038): when the app is unlocked, when it returns to the foreground, a
/// moment after every local change, and regularly while it is open. Background sync needs the
/// database key, which only the PIN releases (ADR 0037), so it doesn't run while locked.
class SyncCoordinator extends Notifier<SyncStatus> {
  Timer? _timer;
  Timer? _debounce;

  @override
  SyncStatus build() {
    ref.onDispose(_stop);
    ref.listen(appDatabaseProvider, (previous, next) {
      if (next.value != null) {
        _start();
      } else {
        _stop();
      }
    }, fireImmediately: true);
    return const SyncStatus();
  }

  Future<SyncOutcome?> syncNow() async {
    final engine = await ref.read(syncEngineProvider.future);
    if (engine == null) return null;
    state = SyncStatus(running: true, outcome: state.outcome, lastSuccess: state.lastSuccess);
    final outcome = await engine.sync();
    if (!ref.mounted) return outcome;
    state = SyncStatus(
      outcome: outcome,
      lastSuccess: outcome == SyncOutcome.done ? DateTime.now() : state.lastSuccess,
    );
    // Photo files follow the records, never holding the sync up (ADR 0040).
    if (outcome == SyncOutcome.done) {
      unawaited(ref.read(uploadCoordinatorProvider.notifier).uploadNow());
      unawaited(_sendTelemetry());
    }
    return outcome;
  }

  /// At most once an hour, after a successful sync (MARA Stage 9).
  Future<void> _sendTelemetry() async {
    final telemetry = await ref.read(telemetryProvider.future);
    if (telemetry == null || !ref.mounted) return;
    await telemetry.sendIfDue(
      ref.read(telemetryTransportProvider),
      ref.read(sessionProvider.notifier).authorized,
    );
  }

  /// A local change was saved: push it soon, but not after every keystroke.
  void changed() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(seconds: 2), () => unawaited(syncNow()));
  }

  void _start() {
    _timer?.cancel();
    final interval = ref.read(syncIntervalProvider);
    if (interval != null) _timer = Timer.periodic(interval, (_) => unawaited(syncNow()));
    unawaited(Future.microtask(syncNow));
  }

  void _stop() {
    _timer?.cancel();
    _debounce?.cancel();
    _timer = null;
    _debounce = null;
  }
}
