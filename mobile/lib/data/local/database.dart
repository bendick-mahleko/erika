import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';

part 'database.g.dart';

/// Small key/value settings (e.g. the sync cursor, the last sync).
class LocalSettings extends Table {
  TextColumn get key => text()();

  TextColumn get value => text()();

  @override
  Set<Column<Object>> get primaryKey => {key};
}

/// Every syncable record (work orders, customers, sites, articles …) as JSON (ADR 0038).
/// `serverData` is the last state the server confirmed (null for a record made here and not
/// yet confirmed); `data` is what the app shows: the server state with the pending changes of
/// the outbox on top.
class SyncRecords extends Table {
  TextColumn get entity => text()();

  TextColumn get id => text()();

  IntColumn get version => integer().withDefault(const Constant(0))();

  TextColumn get fieldVersions => text().withDefault(const Constant('{}'))();

  TextColumn get serverData => text().nullable()();

  TextColumn get data => text()();

  @override
  Set<Column<Object>> get primaryKey => {entity, id};
}

/// Local changes not yet confirmed by the server, in the order they were made. Each change is
/// written together with the record in one transaction, so a killed app loses nothing.
class Outbox extends Table {
  IntColumn get seq => integer().autoIncrement()();

  TextColumn get mutationId => text().unique()();

  TextColumn get entity => text()();

  TextColumn get entityId => text()();

  /// CREATE or UPDATE.
  TextColumn get op => text()();

  /// The changed fields as JSON.
  TextColumn get changes => text()();

  /// For UPDATE: the field versions the change was made against.
  TextColumn get baseVersions => text().withDefault(const Constant('{}'))();

  DateTimeColumn get createdAt => dateTime()();
}

/// The conflict inbox: changes the server decided differently or refused.
class InboxEntries extends Table {
  IntColumn get id => integer().autoIncrement()();

  TextColumn get entity => text()();

  TextColumn get entityId => text()();

  /// CONFLICT or REJECTED.
  TextColumn get kind => text()();

  TextColumn get field => text().nullable()();

  TextColumn get code => text().nullable()();

  TextColumn get serverValue => text().nullable()();

  TextColumn get deviceValue => text().nullable()();

  /// SERVER or DEVICE (for conflicts).
  TextColumn get winner => text().nullable()();

  DateTimeColumn get createdAt => dateTime()();

  BoolColumn get seen => boolean().withDefault(const Constant(false))();
}

/// The app's local database: SQLite through drift, encrypted with SQLCipher
/// (MARA design M1). The key comes from the key vault (PIN or biometrics, ADR 0037)
/// and never leaves the device.
@DriftDatabase(tables: [LocalSettings, SyncRecords, Outbox, InboxEntries])
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
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (m) => m.createAll(),
    onUpgrade: (m, from, to) async {
      if (from < 2) {
        // MARA Stage 4: the sync engine's tables.
        await m.createTable(syncRecords);
        await m.createTable(outbox);
        await m.createTable(inboxEntries);
      }
    },
  );

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
