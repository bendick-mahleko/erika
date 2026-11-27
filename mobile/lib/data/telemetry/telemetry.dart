import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../app/version.dart';
import '../local/database.dart';

/// The counters the server accepts (api/mobile.yaml `TelemetryCounters`).
abstract final class TelemetryCounter {
  static const syncDone = 'syncDone';
  static const syncOffline = 'syncOffline';
  static const syncFailed = 'syncFailed';
  static const pushApplied = 'pushApplied';
  static const pushConflict = 'pushConflict';
  static const pushRejected = 'pushRejected';
  static const uploadDone = 'uploadDone';
  static const uploadOffline = 'uploadOffline';
  static const uploadChunkRetries = 'uploadChunkRetries';
  static const uploadRestarts = 'uploadRestarts';

  /// A maximum of the day, not a sum.
  static const outboxMax = 'outboxMax';
}

/// Sends a report (`PUT /mobile/devices/me/telemetry`).
abstract interface class TelemetryTransport {
  Future<void> send(String accessToken, Map<String, Map<String, int>> days);
}

class TelemetryApi implements TelemetryTransport {
  TelemetryApi(this._client, this._baseUrl);

  final http.Client _client;
  final String _baseUrl;

  @override
  Future<void> send(String accessToken, Map<String, Map<String, int>> days) async {
    final response = await _client.put(
      Uri.parse('$_baseUrl/api/v1/mobile/devices/me/telemetry'),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'X-App-Version': appVersion,
        'Authorization': 'Bearer $accessToken',
      },
      body: jsonEncode({
        'days': [
          for (final e in days.entries) {'day': e.key, 'counters': e.value},
        ],
      }),
    );
    if (response.statusCode != 204) throw StateError('telemetry: HTTP ${response.statusCode}');
  }
}

/// Content-free counters for the field test (MARA Stage 9): how syncs and photo uploads ended,
/// per local day. No record IDs, names or texts, only numbers. Kept in the encrypted database
/// for [keepDays] days and sent at most once per [sendEvery]; the server replaces a day it has,
/// so sending a day again is harmless. Telemetry never breaks the app: every failure is ignored.
class Telemetry {
  Telemetry(this.db, {DateTime Function()? clock, this.keepDays = 14})
    : _clock = clock ?? DateTime.now;

  final AppDatabase db;
  final DateTime Function() _clock;
  final int keepDays;

  static const sendEvery = Duration(hours: 1);
  static const _daysKey = 'telemetry.days';
  static const _sentKey = 'telemetry.lastSent';

  String _day(DateTime t) => t.toLocal().toIso8601String().substring(0, 10);

  /// Adds [by] to [counter] for today.
  Future<void> count(String counter, [int by = 1]) =>
      _update((day) => day[counter] = (day[counter] ?? 0) + by);

  /// Raises a maximum of the day (e.g. the outbox length).
  Future<void> max(String counter, int value) => _update((day) {
    if (value > (day[counter] ?? 0)) day[counter] = value;
  });

  Future<void> _update(void Function(Map<String, int> day) change) async {
    if (keepDays <= 0) return;
    try {
      await db.transaction(() async {
        final days = await stored();
        final today = _day(_clock());
        change(days.putIfAbsent(today, () => <String, int>{}));
        final oldest = _day(_clock().subtract(Duration(days: keepDays)));
        days.removeWhere((day, _) => day.compareTo(oldest) < 0);
        await db.putSetting(_daysKey, jsonEncode(days));
      });
    } on Object {
      // Counting must never break a sync or an upload.
    }
  }

  /// The stored days, oldest first.
  Future<Map<String, Map<String, int>>> stored() async {
    final raw = await db.setting(_daysKey);
    if (raw == null) return {};
    final json = jsonDecode(raw) as Map<String, dynamic>;
    final keys = json.keys.toList()..sort();
    return {
      for (final k in keys)
        k: {
          for (final e in (json[k] as Map<String, dynamic>).entries)
            e.key: (e.value as num).toInt(),
        },
    };
  }

  /// Sends the stored days if the last report is older than [sendEvery] (or [force]).
  Future<bool> sendIfDue(
    TelemetryTransport transport,
    Future<T> Function<T>(Future<T> Function(String accessToken) call) authorized, {
    bool force = false,
  }) async {
    try {
      final last = DateTime.tryParse(await db.setting(_sentKey) ?? '');
      if (!force && last != null && _clock().difference(last) < sendEvery) return false;
      final days = await stored();
      if (days.isEmpty) return false;
      await authorized((token) => transport.send(token, days));
      await db.putSetting(_sentKey, _clock().toUtc().toIso8601String());
      return true;
    } on Object {
      return false;
    }
  }
}
