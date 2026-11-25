import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the database key lives. In the app: the Android Keystore / iOS
/// Keychain ([SecureDatabaseKeyStore]). From MARA Stage 3 the key is released
/// only after PIN or biometric unlock (ADR 0037).
abstract interface class DatabaseKeyStore {
  /// The key as 64 lower-case hex digits; created on first use.
  Future<String> loadOrCreate();
}

class SecureDatabaseKeyStore implements DatabaseKeyStore {
  SecureDatabaseKeyStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  static const _name = 'mara.db.key.v1';
  final FlutterSecureStorage _storage;

  @override
  Future<String> loadOrCreate() async {
    final existing = await _storage.read(key: _name);
    if (existing != null) return existing;
    final key = newDatabaseKey();
    await _storage.write(key: _name, value: key);
    return key;
  }
}

/// 256 random bits from the platform's secure random generator, as hex.
String newDatabaseKey([Random? random]) {
  final rng = random ?? Random.secure();
  return List<String>.generate(
    32,
    (_) => rng.nextInt(256).toRadixString(16).padLeft(2, '0'),
  ).join();
}
