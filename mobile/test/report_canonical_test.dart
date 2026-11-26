import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/domain/rules/report_canonical.dart';
import 'package:mara/features/reports/report_snapshot.dart';

/// The shared vectors of the canonical report (`mara-rules/vectors/report/canonical.json`): the
/// server's Kotlin implementation reads the same file, so a hash made here is accepted there.
void main() {
  final file =
      jsonDecode(File('../mara-rules/vectors/report/canonical.json').readAsStringSync())
          as Map<String, dynamic>;

  test('the vector file is report.canonical', () => expect(file['rule'], 'report.canonical'));

  for (final c in (file['cases'] as List<dynamic>).cast<Map<String, dynamic>>()) {
    test(c['name'] as String, () {
      final canonical = ReportCanonical.canonical(c['input'] as Map<String, Object?>);
      final expected = c['expected'] as Map<String, dynamic>;
      expect(canonical, expected['canonical']);
      expect(ReportCanonical.sha256Of(canonical), expected['sha256']);
    });
  }

  test('verification codes and download keys have the format the server checks', () {
    for (var i = 0; i < 50; i++) {
      expect(newVerificationCode(), matches(RegExp(r'^[A-Z2-7]{10}$')));
      expect(newDownloadKey(), matches(RegExp(r'^[A-Za-z0-9_-]{32}$')));
    }
    expect(newVerificationCode(), isNot(newVerificationCode()));
  });
}
