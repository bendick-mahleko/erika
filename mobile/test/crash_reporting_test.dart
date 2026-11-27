import 'package:flutter_test/flutter_test.dart';
import 'package:mara/app/crash_reporting.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

/// MARA Stage 9: crash reports carry no content (customer names, notes, addresses).
void main() {
  test('messages, request, user and breadcrumbs are removed; type and stack trace stay', () {
    final event = SentryEvent(
      message: SentryMessage('Familie Sommer, Lindenweg 3'),
      exceptions: [
        SentryException(
          type: 'FormatException',
          value: 'Invalid number "Kupferrohr 15 mm"',
          stackTrace: SentryStackTrace(frames: [SentryStackFrame(function: 'parse')]),
        ),
      ],
      user: SentryUser(id: 'u1', email: 'anna@example.test'),
      breadcrumbs: [Breadcrumb(message: 'opened report of Familie Sommer')],
      request: SentryRequest(url: 'https://erika.example/api/v1/mobile/sync/pull?cursor=x'),
    );
    final scrubbed = scrub(event);
    expect(scrubbed.message, isNull);
    expect(scrubbed.user, isNull);
    expect(scrubbed.request, isNull);
    expect(scrubbed.breadcrumbs, isNull);
    expect(scrubbed.exceptions!.single.value, isNull);
    expect(scrubbed.exceptions!.single.type, 'FormatException');
    expect(scrubbed.exceptions!.single.stackTrace!.frames.single.function, 'parse');
  });
}
