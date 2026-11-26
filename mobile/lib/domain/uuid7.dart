import 'dart:math';

/// A UUIDv7 (RFC 9562): 48 bits of Unix milliseconds, then random bits. Made on the phone,
/// so records can be created and referenced offline (ADR 0038, design M-003); time-ordered,
/// which keeps database indexes compact.
String uuid7({DateTime? now, Random? random}) {
  final rng = random ?? Random.secure();
  final ms = (now ?? DateTime.now()).toUtc().millisecondsSinceEpoch;
  final bytes = List<int>.generate(16, (_) => rng.nextInt(256));
  for (var i = 0; i < 6; i++) {
    bytes[i] = (ms >> (8 * (5 - i))) & 0xff;
  }
  bytes[6] = 0x70 | (bytes[6] & 0x0f); // version 7
  bytes[8] = 0x80 | (bytes[8] & 0x3f); // variant 10
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}
