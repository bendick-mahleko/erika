import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';

part 'database.g.dart';

/// Small key/value settings (e.g. the last server check). The syncable tables
/// with their versioning columns follow in MARA Stage 4 (ADR 0038).
class LocalSettings extends Table {
  TextColumn get key => text()();

  TextColumn get value => text()();

  @override
  Set<Column<Object>> get primaryKey => {key};
}

/// The app's local database: SQLite through drift, encrypted with SQLCipher
/// (MARA design M1). The key comes from the platform keystore
/// ([DatabaseKeyStore]) and never leaves the device.
@DriftDatabase(tables: [LocalSettings])
class AppDatabase extends _$AppDatabase {
  AppDatabase(super.executor);

  /// Opens (or creates) the encrypted database in [file] with [hexKey]
  /// (64 hex characters = 256 bits). Fails on the first query if SQLCipher is
  /// missing or the key is wrong: an unencrypted database is never used.
  factory AppDatabase.openEncrypted(File file, String hexKey) {
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(hexKey)) {
      throw ArgumentError('the database key must be 64 lower-case hex digits');
    }
    return AppDatabase(
      NativeDatabase.createInBackground(
        file,
        setup: (db) {
          final cipher = db.select('PRAGMA cipher_version;');
          if (cipher.isEmpty || cipher.first.values.first == null) {
            throw StateError('SQLCipher is not available: refusing to use an unencrypted database');
          }
          db.execute('PRAGMA key = "x\'$hexKey\'";');
          // Reading the schema fails here if the key doesn't match the file.
          db.select('SELECT count(*) FROM sqlite_master;');
        },
      ),
    );
  }

  @override
  int get schemaVersion => 1;

  /// The SQLCipher version, e.g. "4.6.1 community"; null without SQLCipher.
  Future<String?> cipherVersion() async {
    final rows = await customSelect('PRAGMA cipher_version;').get();
    return rows.isEmpty ? null : rows.first.data.values.first as String?;
  }

  Future<String?> setting(String key) async {
    final row = await (select(localSettings)..where((s) => s.key.equals(key))).getSingleOrNull();
    return row?.value;
  }

  Future<void> putSetting(String key, String value) => into(
    localSettings,
  ).insertOnConflictUpdate(LocalSettingsCompanion.insert(key: key, value: value));
}
