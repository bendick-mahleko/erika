import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:mara/data/local/database.dart';
import 'package:mara/data/local/database_key.dart';

/// Runs against the real SQLCipher build that package:sqlite3 bundles
/// (pubspec.yaml `hooks`), on the test machine.
void main() {
  late Directory dir;

  setUp(() => dir = Directory.systemTemp.createTempSync('mara-db-'));
  tearDown(() => dir.deleteSync(recursive: true));

  test('the database is encrypted with SQLCipher and keeps its data', () async {
    final file = File('${dir.path}/mara.sqlite');
    final key = newDatabaseKey();

    final db = AppDatabase.openEncrypted(file, key);
    expect(await db.cipherVersion(), isNotEmpty);
    await db.putSetting('greeting', 'Moin');
    await db.close();

    // The file holds no plain SQLite header and no readable data.
    final bytes = file.readAsBytesSync();
    expect(String.fromCharCodes(bytes.take(15)), isNot('SQLite format 3'));
    expect(String.fromCharCodes(bytes).contains('Moin'), isFalse);

    final again = AppDatabase.openEncrypted(file, key);
    expect(await again.setting('greeting'), 'Moin');
    await again.close();
  });

  test('a wrong key does not open the database', () async {
    final file = File('${dir.path}/mara.sqlite');
    final db = AppDatabase.openEncrypted(file, newDatabaseKey());
    await db.putSetting('k', 'v');
    await db.close();

    final wrong = AppDatabase.openEncrypted(file, newDatabaseKey());
    await expectLater(wrong.setting('k'), throwsA(anything));
    await wrong.close();
  });

  test('keys are 256 random bits as hex', () {
    final a = newDatabaseKey();
    expect(a, matches(RegExp(r'^[0-9a-f]{64}$')));
    expect(newDatabaseKey(), isNot(a));
    expect(() => AppDatabase.openEncrypted(File('${dir.path}/x'), 'short'), throwsArgumentError);
  });
}
