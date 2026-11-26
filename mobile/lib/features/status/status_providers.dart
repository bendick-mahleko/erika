import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/config.dart';
import '../../data/api/status_api.dart';
import '../../data/session/session.dart';
import '../../data/sync/sync_providers.dart';

final statusApiProvider = Provider<StatusApi>(
  (ref) => StatusApi(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);

/// The server's status; refresh with `ref.invalidate(mobileStatusProvider)`.
final mobileStatusProvider = FutureProvider<MobileStatus>(
  (ref) => ref.watch(statusApiProvider).fetch(),
);

/// What the status screen shows about the local database. The opening time is
/// the measurement for spike S1-a (job list in < 1 s on a low-end phone).
class LocalDatabaseInfo {
  const LocalDatabaseInfo({required this.cipherVersion, required this.openTime});

  final String cipherVersion;
  final Duration openTime;
}

/// The open database (opened with the key the PIN or biometrics released, ADR 0037); the time
/// covers opening it and the first query.
final localDatabaseInfoProvider = FutureProvider<LocalDatabaseInfo>((ref) async {
  final session = ref.watch(sessionProvider).value;
  if (session is! Unlocked) throw StateError('locked');
  final watch = Stopwatch()..start();
  final db = await ref.watch(appDatabaseProvider.future);
  if (db == null) throw StateError('locked');
  final version = await db.cipherVersion();
  await db.putSetting('lastOpenedAt', DateTime.now().toUtc().toIso8601String());
  watch.stop();
  return LocalDatabaseInfo(cipherVersion: version ?? '?', openTime: watch.elapsed);
});
