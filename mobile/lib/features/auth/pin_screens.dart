import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/session/session.dart';
import '../../l10n/gen/app_localizations.dart';
import 'widgets.dart';

/// Six digits, not all the same and not a straight run (123456, 654321).
bool isAcceptablePin(String pin) {
  if (!RegExp(r'^[0-9]{6}$').hasMatch(pin)) return false;
  final digits = pin.codeUnits;
  if (digits.toSet().length == 1) return false;
  bool run(int step) {
    for (var i = 1; i < digits.length; i++) {
      if (digits[i] - digits[i - 1] != step) return false;
    }
    return true;
  }

  return !run(1) && !run(-1);
}

TextField _pinField(
  TextEditingController controller,
  String label,
  Key key, {
  bool autofocus = false,
}) => TextField(
  key: key,
  controller: controller,
  autofocus: autofocus,
  obscureText: true,
  keyboardType: TextInputType.number,
  maxLength: 6,
  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
  decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
);

/// After the first sign-in: the PIN wraps the database key (ADR 0037).
class PinSetupScreen extends ConsumerStatefulWidget {
  const PinSetupScreen({super.key});

  @override
  ConsumerState<PinSetupScreen> createState() => _PinSetupScreenState();
}

class _PinSetupScreenState extends ConsumerState<PinSetupScreen> {
  final _pin = TextEditingController();
  final _repeat = TextEditingController();
  var _biometrics = false;
  bool? _biometricsAvailable;
  var _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    ref.read(biometricAuthProvider).isAvailable().then((available) {
      if (mounted) setState(() => _biometricsAvailable = _biometrics = available);
    });
  }

  @override
  void dispose() {
    _pin.dispose();
    _repeat.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final t = AppLocalizations.of(context);
    if (!isAcceptablePin(_pin.text)) return setState(() => _error = t.pinInvalid);
    if (_pin.text != _repeat.text) return setState(() => _error = t.pinMismatch);
    setState(() {
      _busy = true;
      _error = null;
    });
    await ref.read(sessionProvider.notifier).choosePin(_pin.text, biometrics: _biometrics);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(t.pinSetupTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(t.pinSetupIntro),
            const SizedBox(height: 16),
            if (_error != null) ...[ErrorText(_error!), const SizedBox(height: 12)],
            _pinField(_pin, t.pinLabel, const Key('pin'), autofocus: true),
            const SizedBox(height: 8),
            _pinField(_repeat, t.pinRepeatLabel, const Key('pin-repeat')),
            if (_biometricsAvailable == true)
              SwitchListTile(
                key: const Key('biometrics'),
                contentPadding: EdgeInsets.zero,
                title: Text(t.biometricsSwitch),
                value: _biometrics,
                onChanged: (value) => setState(() => _biometrics = value),
              ),
            const SizedBox(height: 16),
            BigButton(label: t.savePin, busy: _busy, onPressed: _save),
          ],
        ),
      ),
    );
  }
}

/// The app is locked: PIN or biometrics release the database key.
class LockScreen extends ConsumerStatefulWidget {
  const LockScreen({super.key});

  @override
  ConsumerState<LockScreen> createState() => _LockScreenState();
}

class _LockScreenState extends ConsumerState<LockScreen> {
  final _pin = TextEditingController();
  var _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // Offer the fingerprint or face check right away, as banking apps do.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final session = ref.read(sessionProvider).value;
      if (session is Locked && session.biometrics) _biometrics();
    });
  }

  @override
  void dispose() {
    _pin.dispose();
    super.dispose();
  }

  Future<void> _unlock() async {
    final t = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    final attempt = await ref.read(sessionProvider.notifier).unlockWithPin(_pin.text);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (!attempt.ok) {
        _error = t.pinWrong(attempt.remaining);
        _pin.clear();
      }
    });
  }

  Future<void> _biometrics() async {
    if (!mounted) return;
    final reason = AppLocalizations.of(context).biometricReason;
    await ref.read(sessionProvider.notifier).unlockWithBiometrics(reason);
  }

  Future<void> _signOut() async {
    final t = AppLocalizations.of(context);
    final confirmed = await confirmSignOut(
      context,
      title: t.signOutConfirmTitle,
      body: t.signOutConfirmBody,
      confirm: t.signOutButton,
      cancel: t.cancel,
    );
    if (confirmed) await ref.read(sessionProvider.notifier).signOut();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final session = ref.watch(sessionProvider).value;
    final locked = session is Locked ? session : null;
    return Scaffold(
      appBar: AppBar(title: Text(t.lockTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (locked != null)
              Text(
                t.lockGreeting(locked.user.displayName),
                style: Theme.of(context).textTheme.titleLarge,
              ),
            if (locked != null) Text(locked.user.workspaceName),
            const SizedBox(height: 16),
            if (_error != null) ...[ErrorText(_error!), const SizedBox(height: 12)],
            _pinField(_pin, t.pinLabel, const Key('pin'), autofocus: true),
            const SizedBox(height: 8),
            BigButton(label: t.unlockButton, busy: _busy, onPressed: _unlock),
            if (locked?.biometrics == true) ...[
              const SizedBox(height: 12),
              OutlinedButton.icon(
                key: const Key('unlock-biometrics'),
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                icon: const Icon(Icons.fingerprint),
                label: Text(t.unlockBiometrics),
                onPressed: _biometrics,
              ),
            ],
            const SizedBox(height: 24),
            TextButton(
              key: const Key('sign-out'),
              style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
              onPressed: _signOut,
              child: Text(t.signOutButton),
            ),
          ],
        ),
      ),
    );
  }
}

/// The server no longer accepts this app version (ADR 0038).
class UpdateRequiredScreen extends ConsumerWidget {
  const UpdateRequiredScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(t.updateTitle)),
      body: SafeArea(
        child: Padding(padding: const EdgeInsets.all(16), child: Text(t.updateBody)),
      ),
    );
  }
}
