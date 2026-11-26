import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:mara/data/api/auth_api.dart';
import 'package:mara/data/media/tus_client.dart';

import 'fake_sync_server.dart';

/// The server's tus endpoint for the app's tests (api/mobile.yaml): parts per photo, offset
/// check, SHA-256 against the photo record, then the photo becomes STORED (the worker's job,
/// done at once here). With switchable faults like a weak mobile network.
class FakeTusServer implements TusTransport {
  FakeTusServer(this.sync, {Random? random}) : _random = random ?? Random(1);

  final FakeSyncServer sync;
  final Random _random;
  final _files = <String, BytesBuilder>{};

  /// Chance that a request fails (connection lost); some of those after the server stored the chunk.
  double failureRate = 0;

  /// The next complete file is treated as damaged (460).
  var corruptNext = false;

  /// How many files were completed (each photo at most once if nothing is duplicated).
  final completed = <String>[];

  /// Every chunk that reached the server.
  var chunks = 0;

  void _maybeFail() {
    if (failureRate > 0 && _random.nextDouble() < failureRate) throw const ApiProblem(0, 'NETWORK');
  }

  @override
  Future<int?> offset(String accessToken, String photoId) async {
    _maybeFail();
    final record = sync.data('photo', photoId);
    if (record == null) return null;
    if (record['state'] != 'PENDING_FILE') return record['sizeBytes'] as int;
    return _files[photoId]?.length ?? 0;
  }

  @override
  Future<TusResult> patch(String accessToken, String photoId, int offset, Uint8List chunk) async {
    _maybeFail();
    final record = sync.data('photo', photoId);
    if (record == null) return const TusGone();
    final size = record['sizeBytes'] as int;
    if (record['state'] != 'PENDING_FILE') return TusAppended(size);
    final file = _files.putIfAbsent(photoId, BytesBuilder.new);
    if (offset != file.length) return const TusOffsetMismatch();
    file.add(chunk);
    chunks++;
    if (file.length < size) {
      // Lost answer: the chunk is stored, the phone doesn't know.
      _maybeFail();
      return TusAppended(file.length);
    }
    // The last chunk: assembled and checked in the same request, as on the server.
    final bytes = file.takeBytes();
    final ok = !corruptNext && sha256.convert(bytes).toString() == record['sha256'];
    corruptNext = false;
    if (!ok) return const TusChecksumMismatch();
    completed.add(photoId);
    sync.officeWrite('photo', photoId, {'state': 'STORED', 'width': 2048, 'height': 1536});
    _maybeFail();
    return TusAppended(size);
  }
}
