import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../../app/config.dart';
import '../api/auth_api.dart';
import '../local/database_key.dart';
import 'device_platform.dart';
import 'key_vault.dart';

/// Why the local data was deleted, for the message on the sign-in screen.
enum WipeReason { none, revoked, pinAttempts, signedOut }

/// Where the app is (ADR 0037). Screens switch on this; only [Unlocked] and
/// [ReauthRequired] hold the database key, and only in memory.
sealed class SessionState {
  const SessionState();
}

class SignedOut extends SessionState {
  const SignedOut([this.reason = WipeReason.none]);

  final WipeReason reason;
}

/// Signed in on the server; the user still has to choose a PIN.
class PinSetup extends SessionState {
  const PinSetup(this.user);

  final DeviceUser user;
}

class Locked extends SessionState {
  const Locked(this.user, {required this.biometrics});

  final DeviceUser user;
  final bool biometrics;
}

class Unlocked extends SessionState {
  const Unlocked(this.user, this.databaseKey, {required this.biometrics});

  final DeviceUser user;
  final String databaseKey;
  final bool biometrics;
}

/// The refresh token has expired (long offline): sign in again; the local data stays.
class ReauthRequired extends SessionState {
  const ReauthRequired(this.user, this.databaseKey, {required this.biometrics});

  final DeviceUser user;
  final String databaseKey;
  final bool biometrics;
}

/// The server no longer accepts this app version.
class UpdateRequired extends SessionState {
  const UpdateRequired(this.user);

  final DeviceUser user;
}

/// Result of a PIN attempt.
class PinAttempt {
  const PinAttempt({required this.ok, this.remaining = 0});

  final bool ok;

  /// Attempts left before the local data is deleted.
  final int remaining;
}

final httpClientProvider = Provider<http.Client>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return client;
});

final authApiProvider = Provider<AuthApi>(
  (ref) => AuthApi(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);
final secureStoreProvider = Provider<SecureStore>((ref) => PlatformSecureStore());
final keyVaultProvider = Provider<KeyVault>((ref) => KeyVault(ref.watch(secureStoreProvider)));
final biometricAuthProvider = Provider<BiometricAuth>((ref) => PlatformBiometricAuth());
final localFilesProvider = Provider<LocalFiles>((ref) => PlatformLocalFiles());
final clockProvider = Provider<DateTime Function()>((ref) => DateTime.now);

final sessionProvider = AsyncNotifierProvider<SessionController, SessionState>(
  SessionController.new,
);

/// Sign-in, PIN, lock, token renewal and the lost-device wipe (ADR 0037, M-NF-07).
class SessionController extends AsyncNotifier<SessionState> {
  static const _account = 'mara.account.v1';
  static const _tokens = 'mara.tokens.v1';

  SecureStore get _store => ref.read(secureStoreProvider);
  KeyVault get _vault => ref.read(keyVaultProvider);
  AuthApi get _api => ref.read(authApiProvider);

  /// The new database key between sign-in and the PIN choice; never stored unwrapped.
  String? _pendingKey;
  Future<DeviceTokens>? _renewing;

  @override
  Future<SessionState> build() async {
    final user = await _readUser();
    if (user == null) return const SignedOut();
    if (!await _vault.isProtected) {
      // The app was closed before a PIN was chosen: start again.
      await _wipe();
      return const SignedOut();
    }
    return Locked(user, biometrics: await _vault.hasBiometricCopy);
  }

  SessionState? get _current => state.value;

  /// After a successful device sign-in.
  Future<void> signedIn(DeviceSession session) async {
    final current = _current;
    if (current is ReauthRequired && current.user.userId == session.user.userId) {
      await _save(session);
      state = AsyncData(
        Unlocked(session.user, current.databaseKey, biometrics: current.biometrics),
      );
      return;
    }
    // A new phone, or someone else signs in: nothing of a previous session stays.
    final files = ref.read(localFilesProvider);
    await files.deleteAll();
    await _store.deleteAll();
    await _save(session);
    _pendingKey = newDatabaseKey();
    state = AsyncData(PinSetup(session.user));
  }

  Future<void> choosePin(String pin, {required bool biometrics}) async {
    final current = _current;
    final key = _pendingKey;
    if (current is! PinSetup || key == null) return;
    await _vault.protect(key, pin, biometrics: biometrics);
    _pendingKey = null;
    state = AsyncData(Unlocked(current.user, key, biometrics: biometrics));
  }

  Future<PinAttempt> unlockWithPin(String pin) async {
    final current = _current;
    if (current is! Locked) return const PinAttempt(ok: false);
    final key = await _vault.unlockWithPin(pin);
    if (key != null) {
      state = AsyncData(Unlocked(current.user, key, biometrics: current.biometrics));
      unawaited(checkDevice());
      return const PinAttempt(ok: true);
    }
    final remaining = KeyVault.maxPinAttempts - await _vault.failures;
    if (remaining <= 0) {
      await _wipe();
      state = const AsyncData(SignedOut(WipeReason.pinAttempts));
    }
    return PinAttempt(ok: false, remaining: remaining < 0 ? 0 : remaining);
  }

  Future<bool> unlockWithBiometrics(String reason) async {
    final current = _current;
    if (current is! Locked || !current.biometrics) return false;
    if (!await ref.read(biometricAuthProvider).authenticate(reason)) return false;
    final key = await _vault.biometricKey();
    if (key == null) return false;
    state = AsyncData(Unlocked(current.user, key, biometrics: true));
    unawaited(checkDevice());
    return true;
  }

  /// Back to the lock screen (5 minutes in the background, or by hand).
  void lock() {
    final current = _current;
    if (current is Unlocked) {
      state = AsyncData(Locked(current.user, biometrics: current.biometrics));
    }
  }

  /// Sign-out in the app: revokes the device on the server (when reachable) and deletes local data.
  Future<void> signOut() async {
    try {
      await authorized(_api.logout);
    } on ApiProblem {
      // Offline or already revoked: the local data goes anyway.
    }
    await _wipe();
    state = const AsyncData(SignedOut(WipeReason.signedOut));
  }

  /// On start and on return to the foreground: a revoked device deletes its data, an outdated
  /// app is stopped. Offline, nothing changes: the app works without the server.
  Future<void> checkDevice() async {
    final current = _current;
    if (current is! Unlocked && current is! Locked && current is! UpdateRequired) return;
    try {
      final status = await authorized(_api.deviceStatus);
      final now = _current;
      if (status.updateRequired) {
        state = AsyncData(UpdateRequired(status.user));
      } else if (now is UpdateRequired) {
        state = AsyncData(Locked(now.user, biometrics: await _vault.hasBiometricCopy));
      }
    } on ApiProblem {
      // Handled in [authorized] or offline.
    }
  }

  /// Runs [call] with the access token. Renews it once when it has expired (never two
  /// renewals at once: a reused refresh token revokes the device). A revoked device deletes
  /// its local data; an expired refresh token asks for a new sign-in.
  Future<T> authorized<T>(Future<T> Function(String accessToken) call) async {
    try {
      var tokens = await _readTokens();
      if (tokens == null) throw const ApiProblem(401, 'UNAUTHORIZED');
      final soon = ref.read(clockProvider)().add(const Duration(seconds: 30));
      if (tokens.accessTokenExpiresAt.isBefore(soon)) tokens = await _renew(tokens);
      try {
        return await call(tokens.accessToken);
      } on ApiProblem catch (problem) {
        if (problem.code != 'TOKEN_EXPIRED') rethrow;
        tokens = await _renew(tokens);
        return await call(tokens.accessToken);
      }
    } on ApiProblem catch (problem) {
      if (problem.deviceRevoked) {
        await _wipe();
        state = const AsyncData(SignedOut(WipeReason.revoked));
      } else if (problem.code == 'REFRESH_TOKEN_INVALID') {
        final current = _current;
        if (current is Unlocked) {
          state = AsyncData(
            ReauthRequired(current.user, current.databaseKey, biometrics: current.biometrics),
          );
        }
      }
      rethrow;
    }
  }

  Future<DeviceTokens> _renew(DeviceTokens old) => _renewing ??= () async {
    try {
      final fresh = await _api.refresh(old.refreshToken);
      await _store.write(_tokens, jsonEncode(fresh.toJson()));
      return fresh;
    } finally {
      _renewing = null;
    }
  }();

  Future<void> _save(DeviceSession session) async {
    await _store.write(
      _account,
      jsonEncode({'deviceId': session.deviceId, 'user': session.user.toJson()}),
    );
    await _store.write(_tokens, jsonEncode(session.tokens.toJson()));
  }

  Future<DeviceUser?> _readUser() async {
    final raw = await _store.read(_account);
    if (raw == null) return null;
    final json = jsonDecode(raw) as Map<String, dynamic>;
    return DeviceUser.fromJson(json['user'] as Map<String, dynamic>);
  }

  Future<DeviceTokens?> _readTokens() async {
    final raw = await _store.read(_tokens);
    return raw == null ? null : DeviceTokens.fromJson(jsonDecode(raw) as Map<String, dynamic>);
  }

  /// Deletes the database, every file and every key: nothing of the session is left.
  Future<void> _wipe() async {
    _pendingKey = null;
    await ref.read(localFilesProvider).deleteAll();
    await _store.deleteAll();
  }
}
