import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/app/version.dart';

void main() {
  test('appVersion matches the version in pubspec.yaml', () {
    final pubspec = File('pubspec.yaml').readAsStringSync();
    final version = RegExp(r'^version:\s*([0-9.]+)', multiLine: true).firstMatch(pubspec)!.group(1);
    expect(appVersion, version);
  });

  test('German and English texts have the same keys', () {
    // Top-level message keys; `@key` metadata and `@@locale` don't count.
    Set<String> keys(String file) =>
        (jsonDecode(File('lib/l10n/$file').readAsStringSync()) as Map<String, dynamic>).keys
            .where((k) => !k.startsWith('@'))
            .toSet();
    expect(keys('app_en.arb'), keys('app_de.arb'));
  });
}
