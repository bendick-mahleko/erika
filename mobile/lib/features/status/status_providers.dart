import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../app/config.dart';
import '../../data/api/status_api.dart';
import '../../data/local/database.dart';
import '../../data/local/database_key.dart';

final httpClientProvider = Provider<http.Client>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return client;
});

final statusApiProvider = Provider<StatusApi>(
  (ref) => StatusApi(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);

/// The server's status; refresh with `ref.invalidate(mobileStatusProvider)`.
final mobileStatusProvider = FutureProvider<MobileStatus>(
  (ref) => ref.watch(statusApiProvider).fetch(),
);

final databaseKeyStoreProvider = Provider<DatabaseKeyStore>((ref) => SecureDatabaseKeyStore());

/// Where the database file lives (app-private storage, excluded from backups
/// from MARA Stage 6 on).
final databaseFileProvider = FutureProvider<File>((ref) async {
  final dir = await getApplicationSupportDirectory();
  return File(p.join(dir.path, 'mara.sqlite'));
});

/// What the status screen shows about the local database. The opening time is
/// the measurement for spike S1-a (job list in < 1 s on a low-end phone).
class LocalDatabaseInfo {
  const LocalDatabaseInfo({required this.cipherVersion, required this.openTime});

  final String cipherVersion;
  final Duration openTime;
}

final localDatabaseInfoProvider = FutureProvider<LocalDatabaseInfo>((ref) async {
  final watch = Stopwatch()..start();
  final key = await ref.watch(databaseKeyStoreProvider).loadOrCreate();
  final file = await ref.watch(databaseFileProvider.future);
  final db = AppDatabase.openEncrypted(file, key);
  try {
    final version = await db.cipherVersion();
    await db.putSetting('lastOpenedAt', DateTime.now().toUtc().toIso8601String());
    watch.stop();
    return LocalDatabaseInfo(cipherVersion: version ?? '?', openTime: watch.elapsed);
  } finally {
    await db.close();
  }
});
