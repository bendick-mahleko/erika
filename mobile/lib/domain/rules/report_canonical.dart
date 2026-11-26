import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:decimal/decimal.dart';

/// The canonical form of a report and its SHA-256 (M-MO-09, M-BR-01): what the customer sees in
/// the preview and signs. The server builds the same input from its data and must get exactly the
/// same text (vectors `mara-rules/vectors/report/canonical.json`, Kotlin
/// `de.erika.reports.ReportCanonical`).
class ReportCanonical {
  ReportCanonical._();

  static const format = 1;

  static const _decimals = {'quantity', 'length', 'width', 'height', 'count', 'result'};
  static const _instants = {'start', 'end', 'signedAt'};

  static String canonical(Map<String, Object?> raw) {
    final out = StringBuffer();
    _write(_normalize(raw, null)!, out);
    return out.toString();
  }

  static String sha256Of(String canonical) => sha256.convert(utf8.encode(canonical)).toString();

  static String hash(Map<String, Object?> raw) => sha256Of(canonical(raw));

  static String _trim(String s) {
    const ws = {0x20, 0x09, 0x0a, 0x0d};
    var start = 0;
    var end = s.length;
    while (start < end && ws.contains(s.codeUnitAt(start))) {
      start++;
    }
    while (end > start && ws.contains(s.codeUnitAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  static Object? _normalize(Object? value, String? key) {
    if (value == null) return null;
    if (value is Map) {
      final out = <String, Object?>{};
      value.forEach((k, v) {
        final n = _normalize(v, k as String);
        if (n != null) out[k] = n;
      });
      return out;
    }
    if (value is List) {
      final items = [for (final v in value) ?_normalize(v, null)];
      final maps = items.whereType<Map<String, Object?>>().toList();
      if (maps.isEmpty || maps.length != items.length) return items;
      final sortKey = maps.first.containsKey('id')
          ? 'id'
          : (maps.first.containsKey('key') ? 'key' : null);
      if (sortKey == null) return items;
      // Indexed, so equal keys keep their order (List.sort isn't stable).
      final indexed = [for (var i = 0; i < maps.length; i++) (i, maps[i])];
      indexed.sort((a, b) {
        final c = ((a.$2[sortKey] as String?) ?? '').compareTo((b.$2[sortKey] as String?) ?? '');
        return c != 0 ? c : a.$1.compareTo(b.$1);
      });
      return [for (final (_, m) in indexed) m];
    }
    if (value is bool || value is int) return value;
    if (value is String) {
      final s = _trim(value);
      if (s.isEmpty) return null;
      if (_decimals.contains(key)) return _decimal(s);
      if (_instants.contains(key)) return _instant(s);
      return s;
    }
    throw ArgumentError('unexpected ${value.runtimeType} in a report');
  }

  static String _decimal(String s) {
    final d = Decimal.parse(s);
    return d == Decimal.zero ? '0' : d.toString();
  }

  static String _instant(String s) {
    final t = DateTime.parse(s.replaceAll('z', 'Z')).toUtc();
    String two(int n) => n.toString().padLeft(2, '0');
    return '${t.year.toString().padLeft(4, '0')}-${two(t.month)}-${two(t.day)}T'
        '${two(t.hour)}:${two(t.minute)}:${two(t.second)}Z';
  }

  static int _compareKeys(String a, String b) => a.compareTo(b);

  static void _write(Object value, StringBuffer out) {
    if (value is Map<String, Object?>) {
      out.write('{');
      final keys = value.keys.toList()..sort(_compareKeys);
      for (var i = 0; i < keys.length; i++) {
        if (i > 0) out.write(',');
        _string(keys[i], out);
        out.write(':');
        _write(value[keys[i]]!, out);
      }
      out.write('}');
    } else if (value is List) {
      out.write('[');
      for (var i = 0; i < value.length; i++) {
        if (i > 0) out.write(',');
        _write(value[i] as Object, out);
      }
      out.write(']');
    } else if (value is bool || value is int) {
      out.write(value.toString());
    } else if (value is String) {
      _string(value, out);
    } else {
      throw ArgumentError('unexpected value');
    }
  }

  static void _string(String s, StringBuffer out) {
    out.write('"');
    for (final unit in s.codeUnits) {
      switch (unit) {
        case 0x22:
          out.write(r'\"');
        case 0x5c:
          out.write(r'\\');
        case 0x0a:
          out.write(r'\n');
        case 0x0d:
          out.write(r'\r');
        case 0x09:
          out.write(r'\t');
        case 0x08:
          out.write(r'\b');
        case 0x0c:
          out.write(r'\f');
        default:
          if (unit < 0x20) {
            out.write('\\u${unit.toRadixString(16).padLeft(4, '0')}');
          } else {
            out.writeCharCode(unit);
          }
      }
    }
    out.write('"');
  }
}
