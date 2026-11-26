import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// IDs two phones compute alike without talking to each other (vectors
/// `mara-rules/vectors/ids/derived.json`, Kotlin: `de.erika.reports.DerivedIds`): a UUIDv8 from
/// SHA-256(namespace bytes ‖ UTF-8 name). Two technicians who both start a work order's report
/// offline, or answer the same checklist item, write to the same record (ADR 0038).
String derivedId(String namespace, String name) {
  final hex = namespace.replaceAll('-', '').toLowerCase();
  if (!RegExp(r'^[0-9a-f]{32}$').hasMatch(hex)) throw ArgumentError('not a UUID: $namespace');
  final ns = Uint8List.fromList([
    for (var i = 0; i < 32; i += 2) int.parse(hex.substring(i, i + 2), radix: 16),
  ]);
  final bytes = sha256.convert([...ns, ...utf8.encode(name)]).bytes.sublist(0, 16);
  bytes[6] = 0x80 | (bytes[6] & 0x0f); // version 8
  bytes[8] = 0x80 | (bytes[8] & 0x3f); // variant 10
  final out = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${out.substring(0, 8)}-${out.substring(8, 12)}-${out.substring(12, 16)}-'
      '${out.substring(16, 20)}-${out.substring(20)}';
}

/// The report of a work order, version [version].
String reportIdFor(String workOrderId, {int version = 1}) =>
    derivedId(workOrderId, 'report/$version');

/// The answer to checklist item [key] of a report.
String checklistAnswerIdFor(String reportId, String key) => derivedId(reportId, 'checklist/$key');
