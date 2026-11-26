import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/domain/rules/conflict_rules.dart';
import 'package:mara/domain/uuid7.dart';

/// The shared sync vectors in `../mara-rules/` (ADR 0038); the back end reads the same file.
void main() {
  final vectors =
      jsonDecode(File('../mara-rules/vectors/sync/field-merge.json').readAsStringSync())
          as Map<String, dynamic>;

  test('the vector file is the field-merge rule', () {
    expect(vectors['rule'], 'sync.field-merge');
  });

  for (final c in (vectors['cases'] as List<dynamic>).cast<Map<String, dynamic>>()) {
    test(c['name'] as String, () {
      final input = c['input'] as Map<String, dynamic>;
      final decision = decideField(
        policy: fieldPolicyFromWire(input['policy'] as String),
        baseVersion: input['baseVersion'] as int,
        serverVersion: input['serverVersion'] as int,
        sameValue: input['sameValue'] as bool,
      );
      expect(fieldDecisionToWire(decision), (c['expected'] as Map<String, dynamic>)['result']);
    });
  }

  test('UUIDv7: version 7, RFC variant, ordered by time', () {
    final a = uuid7(now: DateTime.utc(2027, 3, 1, 8));
    final b = uuid7(now: DateTime.utc(2027, 3, 1, 8, 0, 1));
    expect(
      a,
      matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')),
    );
    expect(a.compareTo(b), lessThan(0));
    expect(uuid7(), isNot(uuid7()));
  });
}
