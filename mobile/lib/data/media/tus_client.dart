import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../../app/version.dart';
import '../api/auth_api.dart';

/// What a PATCH did (api/mobile.yaml, tag `mobile-uploads`).
sealed class TusResult {
  const TusResult();
}

/// Appended; [offset] is where the next chunk starts.
class TusAppended extends TusResult {
  const TusAppended(this.offset);

  final int offset;
}

/// The chunk didn't start at the server's offset; ask with HEAD and resume.
class TusOffsetMismatch extends TusResult {
  const TusOffsetMismatch();
}

/// The complete file didn't match its SHA-256; the server dropped it, start again at 0.
class TusChecksumMismatch extends TusResult {
  const TusChecksumMismatch();
}

/// The photo is gone (deleted, or no longer in the user's scope).
class TusGone extends TusResult {
  const TusGone();
}

/// The phone's side of the tus core protocol on the fixed photo URL (ADR 0040).
abstract interface class TusTransport {
  /// The server's offset for [photoId]; null when the photo is gone.
  Future<int?> offset(String accessToken, String photoId);

  Future<TusResult> patch(String accessToken, String photoId, int offset, Uint8List chunk);
}

class HttpTus implements TusTransport {
  HttpTus(this._client, this._baseUrl);

  final http.Client _client;
  final String _baseUrl;

  static const _timeout = Duration(seconds: 60);

  Uri _uri(String photoId) => Uri.parse('$_baseUrl/api/v1/mobile/uploads/photos/$photoId');

  Map<String, String> _headers(String token) => {
    'Authorization': 'Bearer $token',
    'Tus-Resumable': '1.0.0',
    'X-App-Version': appVersion,
  };

  @override
  Future<int?> offset(String accessToken, String photoId) async {
    final response = await _send(() => _client.head(_uri(photoId), headers: _headers(accessToken)));
    if (response.statusCode == 404) return null;
    _check(response);
    return int.parse(response.headers['upload-offset'] ?? '0');
  }

  @override
  Future<TusResult> patch(String accessToken, String photoId, int offset, Uint8List chunk) async {
    final response = await _send(
      () => _client.patch(
        _uri(photoId),
        headers: {
          ..._headers(accessToken),
          'Upload-Offset': '$offset',
          'Content-Type': 'application/offset+octet-stream',
        },
        body: chunk,
      ),
    );
    return switch (response.statusCode) {
      204 => TusAppended(int.parse(response.headers['upload-offset'] ?? '$offset')),
      409 => const TusOffsetMismatch(),
      460 => const TusChecksumMismatch(),
      404 => const TusGone(),
      _ => throw _problem(response),
    };
  }

  Future<http.Response> _send(Future<http.Response> Function() call) async {
    try {
      return await call().timeout(_timeout);
    } on Exception {
      throw const ApiProblem(0, 'NETWORK');
    }
  }

  void _check(http.Response response) {
    if (response.statusCode >= 400) throw _problem(response);
  }

  ApiProblem _problem(http.Response response) {
    var code = 'HTTP_${response.statusCode}';
    try {
      code = (jsonDecode(response.body) as Map<String, dynamic>)['code'] as String? ?? code;
    } on Object {
      // HEAD answers and some proxies have no JSON body.
    }
    return ApiProblem(response.statusCode, code);
  }
}
