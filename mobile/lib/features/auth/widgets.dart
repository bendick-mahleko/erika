import 'package:flutter/material.dart';

/// A full-width button with a 48 dp tap target (M-NF-05) and a busy state.
class BigButton extends StatelessWidget {
  const BigButton({required this.label, required this.onPressed, this.busy = false, super.key});

  final String label;
  final VoidCallback? onPressed;
  final bool busy;

  @override
  Widget build(BuildContext context) => FilledButton(
    style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
    onPressed: busy ? null : onPressed,
    child: busy
        ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
        : Text(label),
  );
}

/// An information box (e.g. why the data was deleted).
class Notice extends StatelessWidget {
  const Notice({required this.text, super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(text),
    ),
  );
}

/// An error message, announced by screen readers.
class ErrorText extends StatelessWidget {
  const ErrorText(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Text(
      text,
      key: const Key('error'),
      style: TextStyle(color: Theme.of(context).colorScheme.error),
    ),
  );
}

/// Asks before signing out: the local data is deleted (ADR 0037).
Future<bool> confirmSignOut(
  BuildContext context, {
  required String title,
  required String body,
  required String confirm,
  required String cancel,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(cancel)),
          FilledButton(
            key: const Key('confirm-sign-out'),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirm),
          ),
        ],
      ),
    ) ??
    false;
