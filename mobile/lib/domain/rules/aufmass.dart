import 'package:decimal/decimal.dart';

/// Aufmaß calculations (M-MO-06). The same rules exist in Kotlin on the server
/// (`de.erika.reports.AufmassRules`); both must pass the shared vectors in
/// `mara-rules/vectors/aufmass/` (ADR 0036). Decimal arithmetic only: binary
/// floating point gives 1.1 × 1.1 = 1.2100000000000002.
class AufmassRules {
  AufmassRules._();

  /// Results are rounded half away from zero to this many decimals.
  static const scale = 3;

  /// Area of a rectangle: length × width. Dimensions must not be negative.
  static Decimal area(Decimal length, Decimal width) {
    if (length < Decimal.zero || width < Decimal.zero) {
      throw const AufmassException('NEGATIVE_DIMENSION');
    }
    return (length * width).round(scale: scale);
  }

  /// The result as the vectors write it: always [scale] decimals ("12.000").
  static String format(Decimal value) => value.toStringAsFixed(scale);
}

/// A rule violation; [code] is the stable code the shared vectors expect.
class AufmassException implements Exception {
  const AufmassException(this.code);

  final String code;

  @override
  String toString() => 'AufmassException($code)';
}
