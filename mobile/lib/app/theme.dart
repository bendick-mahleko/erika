import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/sync/sync_providers.dart';

/*
 * Ergonomics on site (M-NF-05): tap targets of at least 48 dp (Material's padded tap targets,
 * buttons with a minimum size), and a high-contrast theme for sunlight, used when the phone
 * asks for high contrast or the user switches it on in the app.
 */

const _seed = Color(0xFF1F6F8B);

ThemeData maraTheme({bool highContrast = false}) {
  final scheme = ColorScheme.fromSeed(seedColor: _seed, contrastLevel: highContrast ? 1.0 : 0.0);
  final outline = highContrast ? BorderSide(color: scheme.onSurface, width: 2) : null;
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    materialTapTargetSize: MaterialTapTargetSize.padded,
    visualDensity: VisualDensity.standard,
    outlinedButtonTheme: outline == null
        ? null
        : OutlinedButtonThemeData(style: OutlinedButton.styleFrom(side: outline)),
    inputDecorationTheme: highContrast
        ? InputDecorationTheme(
            border: const OutlineInputBorder(),
            enabledBorder: OutlineInputBorder(borderSide: outline!),
          )
        : null,
    dividerTheme: highContrast ? DividerThemeData(color: scheme.onSurface, thickness: 1) : null,
  );
}

/// The in-app high-contrast switch, stored on the device (in the encrypted database, so it
/// applies once the app is unlocked; the phone's own setting applies always).
final highContrastProvider = NotifierProvider<HighContrast, bool>(HighContrast.new);

class HighContrast extends Notifier<bool> {
  static const key = 'ui.highContrast';

  @override
  bool build() {
    ref.listen(appDatabaseProvider, (_, next) async {
      final db = next.value;
      if (db == null) return;
      final stored = await db.setting(key);
      if (ref.mounted) state = stored == 'true';
    }, fireImmediately: true);
    return false;
  }

  Future<void> toggle() async {
    state = !state;
    final db = await ref.read(appDatabaseProvider.future);
    await db?.putSetting(key, '$state');
  }
}
