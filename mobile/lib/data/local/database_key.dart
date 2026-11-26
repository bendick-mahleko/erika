import 'dart:math';

/// A new database key: 256 random bits from the platform's secure random
/// generator, as 64 lower-case hex digits. Created at the first sign-in on a
/// phone and kept only in the key vault, wrapped by the PIN (ADR 0037).
String newDatabaseKey([Random? random]) {
  final rng = random ?? Random.secure();
  return List<String>.generate(
    32,
    (_) => rng.nextInt(256).toRadixString(16).padLeft(2, '0'),
  ).join();
}
