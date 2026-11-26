import 'dart:convert';

import 'package:cryptography/cryptography.dart';

import 'device_platform.dart';

/// Keeps the database key (ADR 0037): wrapped with a key derived from the
/// user's PIN (PBKDF2-HMAC-SHA256, AES-256-GCM) in the platform keystore, so
/// the key is released only after the PIN. With biometrics switched on, a
/// second copy is read from the keystore only after the fingerprint or face
/// check succeeded.
class KeyVault {
  KeyVault(this._store, {this.iterations = defaultIterations});

  /// About half a second on a low-end phone; the keystore and the attempt
  /// limit ([maxPinAttempts]) protect the wrapped key as well.
  static const defaultIterations = 60000;

  /// After this many wrong PINs in a row the app deletes its local data.
  static const maxPinAttempts = 10;

  static const _wrapped = 'mara.vault.pin.v1';
  static const _biometric = 'mara.vault.biometric.v1';
  static const _failures = 'mara.vault.failures.v1';

  final SecureStore _store;
  final int iterations;
  final _aes = AesGcm.with256bits();

  /// Stores [hexKey] under [pin], and a biometric copy if [biometrics].
  Future<void> protect(String hexKey, String pin, {required bool biometrics}) async {
    final salt = SecretKeyData.random(length: 16).bytes;
    final box = await _aes.encrypt(utf8.encode(hexKey), secretKey: await _derive(pin, salt));
    await _store.write(
      _wrapped,
      jsonEncode({
        'v': 1,
        'iterations': iterations,
        'salt': base64Encode(salt),
        'nonce': base64Encode(box.nonce),
        'cipherText': base64Encode(box.cipherText),
        'mac': base64Encode(box.mac.bytes),
      }),
    );
    if (biometrics) {
      await _store.write(_biometric, hexKey);
    } else {
      await _store.delete(_biometric);
    }
    await _store.delete(_failures);
  }

  Future<bool> get isProtected async => await _store.read(_wrapped) != null;

  Future<bool> get hasBiometricCopy async => await _store.read(_biometric) != null;

  /// The key, or null if [pin] is wrong (counted towards [maxPinAttempts]).
  Future<String?> unlockWithPin(String pin) async {
    final raw = await _store.read(_wrapped);
    if (raw == null) return null;
    final json = jsonDecode(raw) as Map<String, dynamic>;
    final box = SecretBox(
      base64Decode(json['cipherText'] as String),
      nonce: base64Decode(json['nonce'] as String),
      mac: Mac(base64Decode(json['mac'] as String)),
    );
    try {
      final clear = await _aes.decrypt(
        box,
        secretKey: await _derive(
          pin,
          base64Decode(json['salt'] as String),
          rounds: json['iterations'] as int,
        ),
      );
      await _store.delete(_failures);
      return utf8.decode(clear);
    } on SecretBoxAuthenticationError {
      await _store.write(_failures, '${await failures + 1}');
      return null;
    }
  }

  /// The biometric copy; call only after [BiometricAuth.authenticate] succeeded.
  Future<String?> biometricKey() => _store.read(_biometric);

  /// Wrong PINs in a row.
  Future<int> get failures async => int.tryParse(await _store.read(_failures) ?? '') ?? 0;

  Future<SecretKey> _derive(String pin, List<int> salt, {int? rounds}) => Pbkdf2(
    macAlgorithm: Hmac.sha256(),
    iterations: rounds ?? iterations,
    bits: 256,
  ).deriveKeyFromPassword(password: pin, nonce: salt);
}
