import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/local/database_key.dart';
import 'package:mara/data/session/device_platform.dart';
import 'package:mara/data/session/key_vault.dart';

void main() {
  test('the PIN releases the database key; a wrong PIN does not and is counted', () async {
    final store = MemorySecureStore();
    final vault = KeyVault(store, iterations: 1000);
    final key = newDatabaseKey();

    await vault.protect(key, '482913', biometrics: false);
    expect(
      store.values.values.any((v) => v.contains(key)),
      isFalse,
      reason: 'never stored unwrapped',
    );
    expect(await vault.hasBiometricCopy, isFalse);

    expect(await vault.unlockWithPin('000000'), isNull);
    expect(await vault.unlockWithPin('482914'), isNull);
    expect(await vault.failures, 2);

    expect(await vault.unlockWithPin('482913'), key);
    expect(await vault.failures, 0, reason: 'the right PIN resets the count');
  });

  test('the biometric copy exists only when chosen', () async {
    final store = MemorySecureStore();
    final vault = KeyVault(store, iterations: 1000);
    final key = newDatabaseKey();

    await vault.protect(key, '482913', biometrics: true);
    expect(await vault.biometricKey(), key);

    await vault.protect(key, '482913', biometrics: false);
    expect(await vault.biometricKey(), isNull);
  });

  test('the wrapped key keeps its iteration count', () async {
    final store = MemorySecureStore();
    final key = newDatabaseKey();
    await KeyVault(store, iterations: 1000).protect(key, '482913', biometrics: false);
    // A later app version with more rounds still opens what an older one wrapped.
    expect(await KeyVault(store, iterations: 5000).unlockWithPin('482913'), key);
  });
}
