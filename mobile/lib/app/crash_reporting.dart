import 'dart:async';

import 'package:sentry_flutter/sentry_flutter.dart';

import 'config.dart';
import 'version.dart';

/// Crash reports to ERIKA's own GlitchTip (Sentry protocol, MARA Stage 9), only when the build has
/// a DSN (`--dart-define=MARA_SENTRY_DSN=…`). Reports carry the app version, the platform, the
/// exception type and the stack trace, never content: no messages, no breadcrumbs, no screenshots,
/// no user, no request data ([scrub]).
Future<void> runWithCrashReporting(void Function() runApp) async {
  if (AppConfig.sentryDsn.isEmpty) {
    runApp();
    return;
  }
  await SentryFlutter.init((options) {
    options
      ..dsn = AppConfig.sentryDsn
      ..environment = AppConfig.environment
      ..release = 'mara@$appVersion'
      ..sendDefaultPii = false
      ..maxBreadcrumbs = 0
      ..attachScreenshot = false
      ..attachViewHierarchy = false
      ..enableUserInteractionBreadcrumbs = false
      ..enableUserInteractionTracing = false
      ..enableAutoPerformanceTracing = false
      ..enableAutoNativeBreadcrumbs = false
      ..reportPackages = false
      ..beforeSend = ((event, hint) => scrub(event))
      ..beforeBreadcrumb = ((breadcrumb, hint) => null);
  }, appRunner: runApp);
}

/// Removes everything that could hold a customer's or technician's data: exception messages
/// (they may quote values), the event message, request, user, breadcrumbs and extra data.
/// What stays: exception types, stack frames, app and device info.
SentryEvent scrub(SentryEvent event) {
  event
    ..message = null
    ..request = null
    ..user = null
    ..breadcrumbs = null
    // Deprecated in the SDK, but still sent if a library sets it: cleared all the same.
    // ignore: deprecated_member_use
    ..extra = null
    ..transaction = null;
  for (final e in event.exceptions ?? const <SentryException>[]) {
    e.value = null;
  }
  return event;
}
