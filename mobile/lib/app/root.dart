import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/session/session.dart';
import '../features/auth/pin_screens.dart';
import '../features/auth/sign_in_screen.dart';
import '../data/sync/sync_providers.dart';
import '../features/workorders/work_order_list_screen.dart';

/// Shows the screen for the session's state (ADR 0037).
class MaraRoot extends ConsumerWidget {
  const MaraRoot({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    // Locked, signed out or wiped: close every screen opened on top (a work order, the info
    // screen), so nothing stays visible over the lock or sign-in screen.
    ref.listen(sessionProvider, (previous, next) {
      if (previous?.value is Unlocked && next.value is! Unlocked) {
        Navigator.of(context).popUntil((route) => route.isFirst);
      }
    });
    return AutoLock(
      child: switch (session.value) {
        null when session.isLoading => const Scaffold(
          body: Center(child: CircularProgressIndicator()),
        ),
        null => const SignInScreen(),
        SignedOut(:final reason) => SignInScreen(reason: reason),
        PinSetup() => const PinSetupScreen(),
        Locked() => const LockScreen(),
        Unlocked() => const WorkOrderListScreen(),
        ReauthRequired() => const SignInScreen(reauth: true),
        UpdateRequired() => const UpdateRequiredScreen(),
      },
    );
  }
}

/// Locks the app after [after] in the background and checks the device when the app
/// returns (revoked → data deleted; outdated → update screen).
class AutoLock extends ConsumerStatefulWidget {
  const AutoLock({required this.child, this.after = const Duration(minutes: 5), super.key});

  final Widget child;
  final Duration after;

  @override
  ConsumerState<AutoLock> createState() => _AutoLockState();
}

class _AutoLockState extends ConsumerState<AutoLock> with WidgetsBindingObserver {
  DateTime? _backgroundSince;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final now = ref.read(clockProvider);
    final session = ref.read(sessionProvider.notifier);
    switch (state) {
      case AppLifecycleState.hidden || AppLifecycleState.paused:
        _backgroundSince ??= now();
      case AppLifecycleState.resumed:
        final since = _backgroundSince;
        _backgroundSince = null;
        if (since != null && now().difference(since) >= widget.after) session.lock();
        if (since != null) {
          unawaited(session.checkDevice());
          unawaited(ref.read(syncCoordinatorProvider.notifier).syncNow());
        }
      case AppLifecycleState.inactive || AppLifecycleState.detached:
        // Inactive: a system dialog such as the fingerprint prompt; not the background.
        break;
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
