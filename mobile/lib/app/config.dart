/// Build-time configuration, set with `--dart-define`.
///
/// `MARA_API_BASE_URL`: the ERIKA server. The default reaches a server on the
/// developer's machine from the Android emulator (10.0.2.2 is the host).
/// iOS simulator: `--dart-define=MARA_API_BASE_URL=http://localhost:8080`.
class AppConfig {
  AppConfig._();

  static const apiBaseUrl = String.fromEnvironment(
    'MARA_API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );
}
