import 'dart:convert';
import 'dart:io';

import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mara/domain/rules/aufmass.dart';

/// The shared Aufmaß vectors in mara-rules/ (ADR 0036). The back end's
/// AufmassVectorsTest reads the same files, so both sides calculate identically.
void main() {
  Decimal? dec(Map<String, dynamic> m, String k) =>
      m[k] == null ? null : Decimal.parse(m[k] as String);

  void vectors(String path, String rule, String Function(Map<String, dynamic> input) calculate) {
    group(rule, () {
      final file =
          jsonDecode(File('../mara-rules/vectors/aufmass/$path').readAsStringSync())
              as Map<String, dynamic>;
      test('the vector file is $rule', () => expect(file['rule'], rule));
      for (final c in (file['cases'] as List<dynamic>).cast<Map<String, dynamic>>()) {
        test(c['name'] as String, () {
          final input = c['input'] as Map<String, dynamic>;
          final expected = c['expected'] as Map<String, dynamic>;
          if (expected.containsKey('error')) {
            expect(
              () => calculate(input),
              throwsA(isA<AufmassException>().having((e) => e.code, 'code', expected['error'])),
            );
          } else {
            expect(calculate(input), expected['result']);
          }
        });
      }
    });
  }

  vectors(
    'area.json',
    'aufmass.area',
    (i) => AufmassRules.format(AufmassRules.area(dec(i, 'length')!, dec(i, 'width')!)),
  );
  vectors(
    'formula.json',
    'aufmass.formula',
    (i) => AufmassRules.format(AufmassRules.formula(i['expression'] as String)),
  );
  vectors(
    'position.json',
    'aufmass.position',
    (i) => AufmassRules.format(
      AufmassRules.position(
        AufmassInput(
          kind: i['kind'] as String,
          length: dec(i, 'length'),
          width: dec(i, 'width'),
          height: dec(i, 'height'),
          count: dec(i, 'count'),
          expression: i['expression'] as String?,
          deductions: [
            for (final d
                in (i['deductions'] as List<dynamic>? ?? const []).cast<Map<String, dynamic>>())
              AufmassDeductionInput(
                length: dec(d, 'length'),
                width: dec(d, 'width'),
                height: dec(d, 'height'),
                count: dec(d, 'count'),
              ),
          ],
        ),
      ),
    ),
  );

  test('numbers typed with a comma reach the server with a point', () {
    expect(AufmassRules.normalize('4,25'), '4.25');
    expect(AufmassRules.normalize(' '), isNull);
    expect(() => AufmassRules.normalize('-1'), throwsFormatException);
  });
}
