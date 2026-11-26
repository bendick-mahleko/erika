import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/media/media_providers.dart';
import '../../data/sync/sync_providers.dart';
import '../../data/sync/sync_store.dart';
import '../../l10n/gen/app_localizations.dart';
import 'report_providers.dart';

/*
 * MARA Stage 6: photos of a report (M-MO-05). Taken with the camera, kept in the app's own
 * folder, never in the gallery; the record syncs at once, the file follows (ADR 0040).
 */

const photoCategories = ['BEFORE', 'AFTER', 'DEFECT', 'OTHER'];

String photoCategoryLabel(AppLocalizations t, String category) => switch (category) {
  'BEFORE' => t.photoBefore,
  'AFTER' => t.photoAfter,
  'DEFECT' => t.photoDefect,
  _ => t.photoOther,
};

/// Where a photo is on its way, in one short line.
String photoStateLabel(AppLocalizations t, LocalRecord photo, double? progress) {
  if (photo.version == 0) return t.photoNotSent;
  return switch (photo.text('state')) {
    'PENDING_FILE' when progress != null => t.photoUploading((progress * 100).round()),
    'PENDING_FILE' => t.photoWaiting,
    'UPLOADED' => t.photoChecking,
    'STORED' => t.photoStored,
    'REJECTED' =>
      photo.text('rejectReason') == 'VIRUS' ? t.photoRejectedVirus : t.photoRejectedNotImage,
    _ => '',
  };
}

/// Takes a photo and asks for caption and category. Shows the privacy hint before the first
/// photo (M-NF-08). Returns the new photo's ID, or null when cancelled.
Future<String?> takePhoto(
  BuildContext context,
  WidgetRef ref, {
  required String workOrderId,
  String? checklistItemKey,
}) async {
  final t = AppLocalizations.of(context);
  final db = await ref.read(appDatabaseProvider.future);
  if (db != null && await db.setting(_hintKey) != 'true') {
    if (!context.mounted) return null;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const Key('photo-hint'),
        title: Text(t.photoHintTitle),
        content: Text(t.photoHint),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(t.cancel)),
          FilledButton(
            key: const Key('photo-hint-ok'),
            onPressed: () => Navigator.pop(context, true),
            child: Text(t.understood),
          ),
        ],
      ),
    );
    if (ok != true) return null;
    await db.putSetting(_hintKey, 'true');
  }
  final bytes = await ref.read(photoCaptureProvider)();
  if (bytes == null || !context.mounted) return null;
  final details = await showModalBottomSheet<(String?, String)>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => _PhotoDetailsSheet(
      bytes: bytes,
      defaultCategory: checklistItemKey == null ? 'BEFORE' : 'OTHER',
    ),
  );
  if (details == null) return null;
  final (caption, category) = details;
  return ref
      .read(reportWriterProvider)
      .addPhoto(
        workOrderId,
        bytes,
        caption: caption,
        category: category,
        checklistItemKey: checklistItemKey,
      );
}

const _hintKey = 'media.hintShown';

class _PhotoDetailsSheet extends StatefulWidget {
  const _PhotoDetailsSheet({required this.bytes, required this.defaultCategory});

  final Uint8List bytes;
  final String defaultCategory;

  @override
  State<_PhotoDetailsSheet> createState() => _PhotoDetailsSheetState();
}

class _PhotoDetailsSheetState extends State<_PhotoDetailsSheet> {
  final _caption = TextEditingController();
  late String _category = widget.defaultCategory;

  @override
  void dispose() {
    _caption.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        key: const Key('photo-details'),
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.memory(widget.bytes, height: 200, fit: BoxFit.cover, cacheHeight: 400),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              for (final c in photoCategories)
                ChoiceChip(
                  key: Key('category-$c'),
                  label: Text(photoCategoryLabel(t, c)),
                  selected: _category == c,
                  onSelected: (_) => setState(() => _category = c),
                ),
            ],
          ),
          TextField(
            key: const Key('photo-caption'),
            controller: _caption,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.photoCaption),
          ),
          const SizedBox(height: 12),
          FilledButton(
            key: const Key('save-photo'),
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
            onPressed: () => Navigator.pop(context, (
              _caption.text.trim().isEmpty ? null : _caption.text.trim(),
              _category,
            )),
            child: Text(t.saveButton),
          ),
        ],
      ),
    );
  }
}

/// A photo from the app's folder, small.
class PhotoThumbnail extends ConsumerWidget {
  const PhotoThumbnail({required this.photoId, this.size = 96, super.key});

  final String photoId;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) => FutureBuilder<Uint8List?>(
    future: ref.read(photoFilesProvider).read(photoId),
    builder: (context, snapshot) {
      final bytes = snapshot.data;
      if (bytes == null) {
        // Taken on another phone: the file stays on the server.
        return SizedBox.square(dimension: size, child: const Icon(Icons.image_outlined));
      }
      return Image.memory(
        bytes,
        width: size,
        height: size,
        fit: BoxFit.cover,
        cacheWidth: (size * 2).round(),
      );
    },
  );
}

/// The photos of a report, with their upload state, and the camera button.
class PhotosSection extends ConsumerWidget {
  const PhotosSection({required this.view, required this.editable, super.key});

  final ReportView view;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    final upload = ref.watch(uploadCoordinatorProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          header: true,
          child: Text(t.photosSection, style: Theme.of(context).textTheme.titleMedium),
        ),
        if (upload.waitingForWifi)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(t.photosWaitForWifi, style: Theme.of(context).textTheme.bodySmall),
          ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final p in view.photos)
              InkWell(
                key: Key('photo-${p.id}'),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => PhotoScreen(photoId: p.id, editable: editable),
                  ),
                ),
                child: SizedBox(
                  width: 104,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: PhotoThumbnail(photoId: p.id, size: 104),
                      ),
                      Text(
                        photoStateLabel(t, p, upload.progress[p.id]),
                        key: Key('photo-state-${p.id}'),
                        style: Theme.of(context).textTheme.bodySmall,
                        maxLines: 2,
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
        if (editable) ...[
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              key: const Key('take-photo'),
              style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
              icon: const Icon(Icons.photo_camera),
              label: Text(t.takePhoto),
              onPressed: () => takePhoto(context, ref, workOrderId: view.workOrder.id),
            ),
          ),
          Text(t.photoHintShort, style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }
}

/// One photo: large, with caption and category to change, or to delete.
class PhotoScreen extends ConsumerStatefulWidget {
  const PhotoScreen({required this.photoId, required this.editable, super.key});

  final String photoId;
  final bool editable;

  @override
  ConsumerState<PhotoScreen> createState() => _PhotoScreenState();
}

class _PhotoScreenState extends ConsumerState<PhotoScreen> {
  final _caption = TextEditingController();
  var _loaded = false;

  @override
  void dispose() {
    _caption.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final photo = (ref.watch(photoRecordsProvider).value ?? const <LocalRecord>[])
        .where((p) => p.id == widget.photoId && !p.deleted)
        .firstOrNull;
    if (photo == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Padding(padding: const EdgeInsets.all(16), child: Text(t.photoGone)),
      );
    }
    if (!_loaded) {
      _caption.text = photo.text('caption') ?? '';
      _loaded = true;
    }
    final me = ref.watch(currentUserIdProvider);
    final mine =
        ref.watch(isOfficeUserProvider) ||
        photo.text('authorId') == null ||
        photo.text('authorId') == me;
    final editable = widget.editable && mine;
    final writer = ref.read(reportWriterProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(photoCategoryLabel(t, photo.text('category') ?? 'OTHER')),
        actions: [
          if (editable)
            IconButton(
              key: const Key('delete-photo'),
              tooltip: t.deleteButton,
              icon: const Icon(Icons.delete_outline),
              onPressed: () async {
                await writer.delete('photo', photo.id);
                if (context.mounted) Navigator.pop(context);
              },
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          PhotoThumbnail(photoId: photo.id, size: 320),
          const SizedBox(height: 8),
          Text(photoStateLabel(t, photo, ref.watch(uploadCoordinatorProvider).progress[photo.id])),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              for (final c in photoCategories)
                ChoiceChip(
                  label: Text(photoCategoryLabel(t, c)),
                  selected: photo.text('category') == c,
                  onSelected: editable
                      ? (_) => writer.change('photo', photo.id, {'category': c})
                      : null,
                ),
            ],
          ),
          TextField(
            key: const Key('edit-photo-caption'),
            controller: _caption,
            enabled: editable,
            decoration: InputDecoration(labelText: t.photoCaption),
          ),
          const SizedBox(height: 8),
          if (editable)
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                key: const Key('save-photo-caption'),
                style: FilledButton.styleFrom(minimumSize: const Size(120, 48)),
                onPressed: () async {
                  final text = _caption.text.trim();
                  if (text != (photo.text('caption') ?? '')) {
                    await writer.change('photo', photo.id, {'caption': text.isEmpty ? null : text});
                  }
                  if (context.mounted) Navigator.pop(context);
                },
                child: Text(t.saveButton),
              ),
            ),
        ],
      ),
    );
  }
}
