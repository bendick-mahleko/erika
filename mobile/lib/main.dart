import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/root.dart';
import 'l10n/gen/app_localizations.dart';

void main() {
  runApp(const ProviderScope(child: MaraApp()));
}

class MaraApp extends StatelessWidget {
  const MaraApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    // German first (M-NF-12): the device language picks English if set,
    // every other language falls back to the first supported locale (de).
    theme: ThemeData(colorSchemeSeed: const Color(0xFF1F6F8B), useMaterial3: true),
    home: const MaraRoot(),
  );
}
