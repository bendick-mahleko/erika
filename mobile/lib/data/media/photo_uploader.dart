import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import '../api/auth_api.dart';
import '../session/device_platform.dart';
import '../sync/sync_engine.dart';
import '../sync/sync_store.dart';
import '../telemetry/telemetry.dart';
import 'tus_client.dart';

/// The photo files on the phone (ADR 0040): app-private, never in the gallery.
abstract interface class PhotoFiles {
  /// Saves a photo; returns its size and SHA-256 (sent with the record, checked by the server).
  Future<({int size, String sha256})> save(String photoId, Uint8List bytes);

  /// The photo's bytes, or null when this phone doesn't have it (taken on another phone).
  Future<Uint8List?> read(String photoId);

  /// Removes the file (a photo that couldn't be recorded).
  Future<void> delete(String photoId);
}

/// A photo couldn't be stored on the phone, usually because the storage is full (MARA Stage 9
/// robustness test). Nothing was recorded; the technician can free space and take it again.
class PhotoNotSaved implements Exception {
  const PhotoNotSaved(this.cause);

  final Object cause;

  @override
  String toString() => 'PhotoNotSaved(${cause.runtimeType})';
}

/// `<app folder>/photos/<id>.jpg`.
class DevicePhotoFiles implements PhotoFiles {
  DevicePhotoFiles(this._files);

  final LocalFiles _files;

  Future<File> _file(String photoId) async =>
      File('${(await _files.photosDirectory()).path}/$photoId.jpg');

  @override
  Future<({int size, String sha256})> save(String photoId, Uint8List bytes) async {
    final file = await _file(photoId);
    try {
      await file.writeAsBytes(bytes, flush: true);
    } on FileSystemException {
      // A full disk leaves a partial file behind: never keep half a photo.
      if (file.existsSync()) file.deleteSync();
      rethrow;
    }
    return (size: bytes.length, sha256: sha256.convert(bytes).toString());
  }

  @override
  Future<Uint8List?> read(String photoId) async {
    final f = await _file(photoId);
    return f.existsSync() ? f.readAsBytes() : null;
  }

  @override
  Future<void> delete(String photoId) async {
    final f = await _file(photoId);
    if (f.existsSync()) await f.delete();
  }
}

/// In memory (widget tests, where real file I/O doesn't run).
class MemoryPhotoFiles implements PhotoFiles {
  final files = <String, Uint8List>{};

  /// The phone's storage is full: saving fails as on a device (robustness tests).
  var full = false;

  @override
  Future<({int size, String sha256})> save(String photoId, Uint8List bytes) async {
    if (full) throw const FileSystemException('No space left on device');
    files[photoId] = bytes;
    return (size: bytes.length, sha256: sha256.convert(bytes).toString());
  }

  @override
  Future<Uint8List?> read(String photoId) async => files[photoId];

  @override
  Future<void> delete(String photoId) async => files.remove(photoId);
}

/// How an upload run ended.
enum UploadOutcome { done, offline, skipped }

/// Sends the files of photos whose record the server has (state PENDING_FILE), with tus: 1 MB
/// chunks, resumed from the server's offset after any interruption (ADR 0040). Runs after the
/// data sync and never blocks it; one run at a time. A file whose hash the server refused is
/// sent again from the start (at most twice per run).
class PhotoUploader {
  PhotoUploader({
    required this.store,
    required this.files,
    required this.transport,
    required this.authorized,
    this.chunkSize = 1024 * 1024,
    this.telemetry,
  });

  /// Content-free counters (MARA Stage 9); null in most tests.
  final Telemetry? telemetry;

  final SyncStore store;
  final PhotoFiles files;
  final TusTransport transport;
  final Authorized authorized;
  final int chunkSize;

  /// Bytes sent per photo during the current run (for the progress on screen).
  final progress = <String, double>{};
  void Function()? onProgress;

  Future<UploadOutcome>? _running;

  Future<UploadOutcome> run() => _running ??= _run().whenComplete(() => _running = null);

  /// Photos that wait for their file: confirmed by the server (version > 0), not deleted here.
  Future<List<LocalRecord>> waiting() async => [
    for (final r in await store.watch('photo').first)
      if (r.version > 0 && !r.deleted && r.text('state') == 'PENDING_FILE') r,
  ];

  Future<UploadOutcome> _run() async {
    try {
      for (final photo in await waiting()) {
        final bytes = await files.read(photo.id);
        if (bytes == null) continue; // taken on another phone
        await _upload(photo.id, bytes);
      }
      await telemetry?.count(TelemetryCounter.uploadDone);
      return UploadOutcome.done;
    } on ApiProblem catch (problem) {
      if (problem.isNetwork) {
        await telemetry?.count(TelemetryCounter.uploadOffline);
        return UploadOutcome.offline;
      }
      rethrow;
    } finally {
      progress.clear();
      onProgress?.call();
    }
  }

  Future<void> _upload(String id, Uint8List bytes) async {
    var restarts = 0;
    var offset = await authorized<int?>((token) => transport.offset(token, id));
    while (offset != null && offset < bytes.length) {
      final end = (offset + chunkSize).clamp(0, bytes.length);
      final from = offset;
      final result = await authorized(
        (token) => transport.patch(token, id, from, Uint8List.sublistView(bytes, from, end)),
      );
      switch (result) {
        case TusAppended(offset: final reached):
          progress[id] = reached / bytes.length;
          onProgress?.call();
          offset = reached;
        case TusOffsetMismatch():
          await telemetry?.count(TelemetryCounter.uploadChunkRetries);
          offset = await authorized<int?>((token) => transport.offset(token, id));
        case TusChecksumMismatch():
          await telemetry?.count(TelemetryCounter.uploadRestarts);
          if (++restarts > 2) return;
          offset = 0;
        case TusGone():
          return;
      }
    }
  }
}
