import 'dart:convert';
import 'dart:io';

import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/domain/rules/aufmass.dart';

/// The shared Aufmaß vectors in mara-rules/ (ADR 0036). The back end's
/// AufmassVectorsTest reads the same file, so both sides calculate identically.
void main() {
  final vectors =
      jsonDecode(File('../mara-rules/vectors/aufmass/area.json').readAsStringSync())
          as Map<String, dynamic>;

  test('the vector file is for aufmass.area', () {
    expect(vectors['rule'], 'aufmass.area');
  });

  for (final c in (vectors['cases'] as List<dynamic>).cast<Map<String, dynamic>>()) {
    test(c['name'] as String, () {
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expected'] as Map<String, dynamic>;
      final length = Decimal.parse(input['length'] as String);
      final width = Decimal.parse(input['width'] as String);
      if (expected.containsKey('error')) {
        expect(
          () => AufmassRules.area(length, width),
          throwsA(isA<AufmassException>().having((e) => e.code, 'code', expected['error'])),
        );
      } else {
        expect(AufmassRules.format(AufmassRules.area(length, width)), expected['result']);
      }
    });
  }
}
