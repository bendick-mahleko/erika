import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/root.dart';
import 'app/theme.dart';
import 'l10n/gen/app_localizations.dart';

void main() {
  runApp(const ProviderScope(child: MaraApp()));
}

class MaraApp extends ConsumerWidget {
  const MaraApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp(
    onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    // German first (M-NF-12): the device language picks English if set,
    // every other language falls back to the first supported locale (de).
    theme: maraTheme(highContrast: ref.watch(highContrastProvider)),
    highContrastTheme: maraTheme(highContrast: true),
    home: const MaraRoot(),
  );
}
