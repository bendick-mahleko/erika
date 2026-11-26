import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/api/auth_api.dart';
import '../../data/session/session.dart';
import '../../l10n/gen/app_localizations.dart';
import 'widgets.dart';

enum _Step { credentials, code, enrolment, recoveryCodes }

/// Device sign-in (ADR 0037): email and password, then a TOTP code, a recovery
/// code, or, for an account without 2FA, the set-up of an authenticator app.
class SignInScreen extends ConsumerStatefulWidget {
  const SignInScreen({super.key, this.reason = WipeReason.none, this.reauth = false});

  final WipeReason reason;

  /// The refresh token expired; the local data stays.
  final bool reauth;

  @override
  ConsumerState<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends ConsumerState<SignInScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _code = TextEditingController();
  final _deviceName = TextEditingController(text: Platform.isIOS ? 'iPhone' : 'Android-Telefon');
  var _step = _Step.credentials;
  var _useRecovery = false;
  var _busy = false;
  String? _error;
  TotpEnrolment? _enrolment;
  DeviceSession? _session;

  @override
  void dispose() {
    for (final c in [_email, _password, _code, _deviceName]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    final api = ref.read(authApiProvider);
    final code = _code.text.trim();
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final session = await api.deviceLogin(
        email: _email.text.trim(),
        password: _password.text,
        code: _step != _Step.credentials && !_useRecovery ? code : null,
        recoveryCode: _step == _Step.code && _useRecovery ? code : null,
        deviceName: _deviceName.text.trim().isEmpty ? 'Telefon' : _deviceName.text.trim(),
        platform: Platform.isIOS ? 'IOS' : 'ANDROID',
      );
      final codes = session.recoveryCodes;
      if (codes != null && codes.isNotEmpty) {
        setState(() {
          _session = session;
          _step = _Step.recoveryCodes;
        });
      } else {
        await ref.read(sessionProvider.notifier).signedIn(session);
      }
    } on ApiProblem catch (problem) {
      if (problem.code == 'TOTP_REQUIRED') {
        setState(() => _step = _Step.code);
      } else if (problem.code == 'TOTP_ENROLMENT_REQUIRED') {
        await _startEnrolment();
      } else {
        setState(() => _error = problemText(AppLocalizations.of(context), problem));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _startEnrolment() async {
    try {
      final enrolment = await ref
          .read(authApiProvider)
          .startEnrolment(_email.text.trim(), _password.text);
      setState(() {
        _enrolment = enrolment;
        _step = _Step.enrolment;
        _code.clear();
      });
    } on ApiProblem catch (problem) {
      if (mounted) setState(() => _error = problemText(AppLocalizations.of(context), problem));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final notice = switch (widget.reason) {
      WipeReason.revoked => t.reasonRevoked,
      WipeReason.pinAttempts => t.reasonPinAttempts,
      WipeReason.signedOut => t.reasonSignedOut,
      WipeReason.none => widget.reauth ? t.reauthIntro : null,
    };
    return Scaffold(
      appBar: AppBar(
        title: Text(switch (_step) {
          _Step.enrolment => t.enrolTitle,
          _Step.recoveryCodes => t.recoveryTitle,
          _ => t.signInTitle,
        }),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (notice != null && _step == _Step.credentials) ...[
              Notice(text: notice, key: const Key('sign-in-notice')),
              const SizedBox(height: 16),
            ],
            if (_error != null) ...[ErrorText(_error!), const SizedBox(height: 16)],
            ...switch (_step) {
              _Step.credentials => _credentials(t),
              _Step.code => _codeStep(t),
              _Step.enrolment => _enrolmentStep(t),
              _Step.recoveryCodes => _recoveryStep(t),
            },
          ],
        ),
      ),
    );
  }

  List<Widget> _credentials(AppLocalizations t) => [
    Text(t.signInIntro),
    const SizedBox(height: 16),
    TextField(
      key: const Key('email'),
      controller: _email,
      keyboardType: TextInputType.emailAddress,
      autofillHints: const [AutofillHints.email],
      autocorrect: false,
      decoration: InputDecoration(labelText: t.emailLabel, border: const OutlineInputBorder()),
    ),
    const SizedBox(height: 12),
    TextField(
      key: const Key('password'),
      controller: _password,
      obscureText: true,
      autofillHints: const [AutofillHints.password],
      decoration: InputDecoration(labelText: t.passwordLabel, border: const OutlineInputBorder()),
    ),
    const SizedBox(height: 12),
    TextField(
      key: const Key('device-name'),
      controller: _deviceName,
      maxLength: 80,
      decoration: InputDecoration(
        labelText: t.deviceNameLabel,
        helperText: t.deviceNameHint,
        border: const OutlineInputBorder(),
      ),
    ),
    const SizedBox(height: 16),
    BigButton(label: t.continueButton, busy: _busy, onPressed: _submit),
  ];

  List<Widget> _codeStep(AppLocalizations t) => [
    Text(t.codeIntro),
    const SizedBox(height: 16),
    TextField(
      key: const Key('code'),
      controller: _code,
      autofocus: true,
      keyboardType: _useRecovery ? TextInputType.text : TextInputType.number,
      autofillHints: const [AutofillHints.oneTimeCode],
      decoration: InputDecoration(
        labelText: _useRecovery ? t.recoveryCodeLabel : t.codeLabel,
        border: const OutlineInputBorder(),
      ),
    ),
    const SizedBox(height: 8),
    Align(
      alignment: Alignment.centerLeft,
      child: TextButton(
        style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
        onPressed: () => setState(() => _useRecovery = !_useRecovery),
        child: Text(_useRecovery ? t.codeLabel : t.useRecoveryCode),
      ),
    ),
    const SizedBox(height: 8),
    BigButton(label: t.signInButton, busy: _busy, onPressed: _submit),
  ];

  List<Widget> _enrolmentStep(AppLocalizations t) {
    final secret = _enrolment!.secret;
    final grouped = RegExp('.{1,4}').allMatches(secret).map((m) => m.group(0)).join(' ');
    return [
      Text(t.enrolIntro),
      const SizedBox(height: 12),
      SelectableText(
        grouped,
        key: const Key('totp-secret'),
        style: Theme.of(context).textTheme.titleMedium?.copyWith(fontFamily: 'monospace'),
      ),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
          icon: const Icon(Icons.copy),
          label: Text(t.copySecret),
          onPressed: () => _copy(secret, t),
        ),
      ),
      const SizedBox(height: 12),
      Text(t.enrolCodeIntro),
      const SizedBox(height: 12),
      TextField(
        key: const Key('code'),
        controller: _code,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(labelText: t.codeLabel, border: const OutlineInputBorder()),
      ),
      const SizedBox(height: 16),
      BigButton(label: t.confirmButton, busy: _busy, onPressed: _submit),
    ];
  }

  List<Widget> _recoveryStep(AppLocalizations t) {
    final codes = _session!.recoveryCodes!;
    return [
      Text(t.recoveryIntro),
      const SizedBox(height: 12),
      SelectableText(
        codes.join('\n'),
        key: const Key('recovery-codes'),
        style: Theme.of(context).textTheme.titleMedium?.copyWith(fontFamily: 'monospace'),
      ),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
          icon: const Icon(Icons.copy),
          label: Text(t.copyCodes),
          onPressed: () => _copy(codes.join('\n'), t),
        ),
      ),
      const SizedBox(height: 16),
      BigButton(
        label: t.recoveryDone,
        busy: _busy,
        onPressed: () => ref.read(sessionProvider.notifier).signedIn(_session!),
      ),
    ];
  }

  Future<void> _copy(String text, AppLocalizations t) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(t.copied)));
  }
}

/// The text for an error answer of the sign-in endpoints.
String problemText(AppLocalizations t, ApiProblem problem) => switch (problem.code) {
  'INVALID_CREDENTIALS' => t.errorInvalidCredentials,
  'INVALID_CODE' => t.errorInvalidCode,
  'EMAIL_NOT_VERIFIED' => t.errorEmailNotVerified,
  'APP_NOT_FOR_ROLE' => t.errorAppNotForRole,
  'RATE_LIMITED' => t.errorRateLimited,
  'APP_UPDATE_REQUIRED' => t.errorUpdateRequired,
  'FEATURE_DISABLED' => t.errorNotAvailable,
  'NETWORK' => t.errorNetwork,
  _ => t.errorGeneric,
};
