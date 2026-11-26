import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:local_auth/local_auth.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// The platform keystore (Android Keystore / iOS Keychain), behind an interface
/// so tests can use [MemorySecureStore].
abstract interface class SecureStore {
  Future<String?> read(String key);

  Future<void> write(String key, String value);

  Future<void> delete(String key);

  Future<void> deleteAll();
}

class PlatformSecureStore implements SecureStore {
  PlatformSecureStore([FlutterSecureStorage? storage])
    : _storage =
          storage ??
          const FlutterSecureStorage(
            // Readable only while the phone is unlocked, never restored to another device.
            iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
          );

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) => _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);

  @override
  Future<void> deleteAll() => _storage.deleteAll();
}

class MemorySecureStore implements SecureStore {
  final values = <String, String>{};

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async => values[key] = value;

  @override
  Future<void> delete(String key) async => values.remove(key);

  @override
  Future<void> deleteAll() async => values.clear();
}

/// Fingerprint or face unlock.
abstract interface class BiometricAuth {
  Future<bool> isAvailable();

  /// Asks the user; true when they were recognised.
  Future<bool> authenticate(String reason);
}

class PlatformBiometricAuth implements BiometricAuth {
  final _auth = LocalAuthentication();

  @override
  Future<bool> isAvailable() async {
    try {
      return await _auth.isDeviceSupported() && await _auth.canCheckBiometrics;
    } on Object {
      return false;
    }
  }

  @override
  Future<bool> authenticate(String reason) async {
    try {
      return await _auth.authenticate(localizedReason: reason, biometricOnly: true);
    } on Object {
      return false;
    }
  }
}

/// The app's files on the device: the encrypted database and, from Stage 6, photos.
abstract interface class LocalFiles {
  Future<File> databaseFile();

  /// Photos taken in the app (MARA Stage 6): app-private, never in the gallery, excluded from
  /// cloud backups (ADR 0040).
  Future<Directory> photosDirectory();

  /// Deletes the database and every other file the app keeps (lost device, sign-out).
  Future<void> deleteAll();
}

class PlatformLocalFiles implements LocalFiles {
  static const _channel = MethodChannel('mara/files');
  var _excluded = false;

  @override
  Future<File> databaseFile() async => File(p.join((await _appDirectory()).path, 'mara.sqlite'));

  @override
  Future<Directory> photosDirectory() async =>
      Directory(p.join((await _appDirectory()).path, 'photos')).create(recursive: true);

  /// Application Support, excluded from iCloud backups once per start (Android: the manifest
  /// switches backups off).
  Future<Directory> _appDirectory() async {
    final dir = await getApplicationSupportDirectory();
    if (!_excluded && Platform.isIOS) {
      try {
        await _channel.invokeMethod<bool>('excludeFromBackup', dir.path);
        _excluded = true;
      } on PlatformException {
        // Tried again on the next start; the files stay app-private either way.
      }
    }
    return dir;
  }

  @override
  Future<void> deleteAll() async {
    for (final dir in [await getApplicationSupportDirectory(), await getTemporaryDirectory()]) {
      if (!dir.existsSync()) continue;
      for (final entry in dir.listSync()) {
        try {
          entry.deleteSync(recursive: true);
        } on FileSystemException {
          // A file still held open by the platform; the next wipe or the OS removes it.
        }
      }
    }
  }
}
