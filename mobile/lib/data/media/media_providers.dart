import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../app/config.dart';
import '../session/session.dart';
import '../sync/sync_providers.dart';
import 'photo_uploader.dart';
import 'tus_client.dart';

/*
 * MARA Stage 6: photos (M-MO-05, ADR 0040). Taken with the phone's camera, compressed on capture
 * (long side 2,048 px, JPEG quality 80), kept in the app's own folder, uploaded with tus after
 * the data sync.
 */

/// Takes a photo with the camera: compressed by the platform to 2,048 px at quality 80, written
/// to the app's cache (never the gallery). Null when the user cancels. Overridden in tests.
final photoCaptureProvider = Provider<Future<Uint8List?> Function()>(
  (ref) => () async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.camera,
      maxWidth: 2048,
      maxHeight: 2048,
      imageQuality: 80,
      requestFullMetadata: false,
    );
    return picked?.readAsBytes();
  },
);

/// Whether the phone is on Wi-Fi (for "Wi-Fi first"). Overridden in tests.
final onWifiProvider = Provider<Future<bool> Function()>(
  (ref) =>
      () async => (await Connectivity().checkConnectivity()).contains(ConnectivityResult.wifi),
);

final tusTransportProvider = Provider<TusTransport>(
  (ref) => HttpTus(ref.watch(httpClientProvider), AppConfig.apiBaseUrl),
);

final photoFilesProvider = Provider<PhotoFiles>(
  (ref) => DevicePhotoFiles(ref.watch(localFilesProvider)),
);

final photoUploaderProvider = FutureProvider<PhotoUploader?>((ref) async {
  final store = await ref.watch(syncStoreProvider.future);
  if (store == null) return null;
  return PhotoUploader(
    store: store,
    files: ref.watch(photoFilesProvider),
    transport: ref.watch(tusTransportProvider),
    authorized: ref.read(sessionProvider.notifier).authorized,
  );
});

/// The upload's state for the screens: progress per photo while a run is on.
class UploadStatus {
  const UploadStatus({this.running = false, this.progress = const {}, this.waitingForWifi = false});

  final bool running;
  final Map<String, double> progress;

  /// "Wi-Fi first" is on and the phone isn't on Wi-Fi: uploads wait.
  final bool waitingForWifi;
}

final uploadCoordinatorProvider = NotifierProvider<UploadCoordinator, UploadStatus>(
  UploadCoordinator.new,
);

/// Starts photo uploads after every data sync, so the photo records always arrive first and a
/// slow upload never holds the sync up (ADR 0040).
class UploadCoordinator extends Notifier<UploadStatus> {
  static const wifiOnlyKey = 'media.wifiOnly';

  @override
  UploadStatus build() => const UploadStatus();

  Future<UploadOutcome?> uploadNow() async {
    final uploader = await ref.read(photoUploaderProvider.future);
    if (uploader == null || !ref.mounted) return null;
    final db = await ref.read(appDatabaseProvider.future);
    final wifiOnly = await db?.setting(wifiOnlyKey) == 'true';
    if (wifiOnly && !await ref.read(onWifiProvider)()) {
      if (ref.mounted) state = const UploadStatus(waitingForWifi: true);
      return UploadOutcome.skipped;
    }
    if (!ref.mounted) return null;
    state = const UploadStatus(running: true);
    uploader.onProgress = () {
      if (ref.mounted) state = UploadStatus(running: true, progress: Map.of(uploader.progress));
    };
    final outcome = await uploader.run();
    if (ref.mounted) state = const UploadStatus();
    return outcome;
  }
}

/// "Wi-Fi first": photos upload only on Wi-Fi (the data sync always runs).
final wifiOnlyProvider = NotifierProvider<WifiOnly, bool>(WifiOnly.new);

class WifiOnly extends Notifier<bool> {
  @override
  bool build() {
    ref.listen(appDatabaseProvider, (_, next) async {
      final db = next.value;
      if (db == null) return;
      final stored = await db.setting(UploadCoordinator.wifiOnlyKey);
      if (ref.mounted) state = stored == 'true';
    }, fireImmediately: true);
    return false;
  }

  Future<void> toggle() async {
    state = !state;
    final db = await ref.read(appDatabaseProvider.future);
    await db?.putSetting(UploadCoordinator.wifiOnlyKey, '$state');
    if (!state) unawaited(ref.read(uploadCoordinatorProvider.notifier).uploadNow());
  }
}
