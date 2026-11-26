import 'package:decimal/decimal.dart';
import 'package:rational/rational.dart';

/// Aufmaß calculations (M-MO-06). The same rules exist in Kotlin on the server
/// (`de.erika.reports.AufmassRules`); both must pass the shared vectors in
/// `mara-rules/vectors/aufmass/` (ADR 0036). Decimal arithmetic only: binary
/// floating point gives 1.1 × 1.1 = 1.2100000000000002.
class AufmassRules {
  AufmassRules._();

  /// Results are rounded half away from zero to this many decimals.
  static const scale = 3;

  /// Every division is rounded half away from zero to this many decimals before the next step.
  static const divisionScale = 10;

  /// Version of the position and formula rules (the server stores the one it used).
  static const version = 1;

  static const maxFormula = 200;

  static const kinds = ['LENGTH', 'AREA', 'VOLUME', 'PIECE', 'FORMULA'];

  /// How many dimensions a kind has: LENGTH 1, AREA 2, VOLUME 3, PIECE 0.
  static const dimensions = {'LENGTH': 1, 'AREA': 2, 'VOLUME': 3, 'PIECE': 0};

  /// Area of a rectangle: length × width. Dimensions must not be negative.
  static Decimal area(Decimal length, Decimal width) {
    if (length < Decimal.zero || width < Decimal.zero) {
      throw const AufmassException('NEGATIVE_DIMENSION');
    }
    return roundHalfAway(length * width, scale);
  }

  /// One position (vectors `aufmass/position.json`): LENGTH, AREA, VOLUME or PIECE times
  /// count, minus its deductions (each line rounded first), or a FORMULA.
  static Decimal position(AufmassInput p) {
    if (p.kind == 'FORMULA') {
      if (p.deductions.isNotEmpty) throw const AufmassException('DEDUCTION_NOT_ALLOWED');
      return formula(p.expression ?? '');
    }
    final dims = dimensions[p.kind];
    if (dims == null) throw const AufmassException('UNKNOWN_KIND');
    if (p.kind == 'PIECE' && p.deductions.isNotEmpty) {
      throw const AufmassException('DEDUCTION_NOT_ALLOWED');
    }
    final main = _line(dims, p.length, p.width, p.height, p.count);
    var deducted = Decimal.zero;
    for (final d in p.deductions) {
      deducted += _line(dims, d.length, d.width, d.height, d.count);
    }
    final result = main - deducted;
    if (result < Decimal.zero) throw const AufmassException('NEGATIVE_RESULT');
    return result;
  }

  static Decimal _line(int dims, Decimal? length, Decimal? width, Decimal? height, Decimal? count) {
    final values = [length, width, height].take(dims).toList();
    if (values.any((v) => v == null)) throw const AufmassException('MISSING_DIMENSION');
    final factor = count ?? Decimal.one;
    if (factor < Decimal.zero || values.any((v) => v! < Decimal.zero)) {
      throw const AufmassException('NEGATIVE_DIMENSION');
    }
    var product = factor;
    for (final v in values) {
      product *= v!;
    }
    return roundHalfAway(product, scale);
  }

  /// A simple formula, e.g. `2*(4,25+3,10)*2,5` (vectors `aufmass/formula.json`).
  static Decimal formula(String expression) {
    if (expression.length > maxFormula || expression.trim().isEmpty) {
      throw const AufmassException('INVALID_FORMULA');
    }
    final parser = _FormulaParser(_token.allMatches(expression).map((m) => m.group(0)!).toList());
    final value = parser.expression();
    if (!parser.atEnd) throw const AufmassException('INVALID_FORMULA');
    final result = roundHalfAway(value, scale);
    if (result < Decimal.zero) throw const AufmassException('NEGATIVE_RESULT');
    return result;
  }

  static final _token = RegExp(r'[0-9]+(?:[.,][0-9]+)?|[-+*/×÷()]|\S');
  static final _number = RegExp(r'^[0-9]+(?:[.,][0-9]+)?$');

  /// Rounds half away from zero to [decimals] places.
  static Decimal roundHalfAway(Decimal value, int decimals) =>
      _roundRational(value.toRational(), decimals);

  static Decimal _roundRational(Rational value, int decimals) {
    final scaled = value * Rational(BigInt.from(10).pow(decimals));
    final n = scaled.numerator.abs();
    final d = scaled.denominator;
    var q = n ~/ d;
    if ((n % d) * BigInt.two >= d) q += BigInt.one;
    final signed = scaled.numerator.isNegative ? -q : q;
    return (Decimal.fromBigInt(signed) / Decimal.fromBigInt(BigInt.from(10).pow(decimals)))
        .toDecimal();
  }

  /// The result as the vectors and the server write it: always [scale] decimals ("12.000").
  static String format(Decimal value) => value.toStringAsFixed(scale);

  /// A number typed on the phone ("4,25" or "4.25") as the server expects it ("4.25"); null when
  /// empty, [FormatException] when it isn't a non-negative decimal of up to 6 places.
  static String? normalize(String? text) {
    final t = text?.trim().replaceAll(',', '.') ?? '';
    if (t.isEmpty) return null;
    if (!RegExp(r'^[0-9]{1,9}(\.[0-9]{1,6})?$').hasMatch(t)) {
      throw FormatException('not a decimal: $text');
    }
    return Decimal.parse(t).toString();
  }
}

class _FormulaParser {
  _FormulaParser(this.tokens);

  final List<String> tokens;
  var _pos = 0;

  bool get atEnd => _pos >= tokens.length;

  String? _peek() => _pos < tokens.length ? tokens[_pos] : null;

  String? _take() {
    final token = _peek();
    _pos++;
    return token;
  }

  Decimal expression() {
    var value = _term();
    while (_peek() == '+' || _peek() == '-') {
      final op = _take();
      final right = _term();
      value = op == '+' ? value + right : value - right;
    }
    return value;
  }

  Decimal _term() {
    var value = _factor();
    while (const {'*', '/', '×', '÷'}.contains(_peek())) {
      final op = _take();
      final right = _factor();
      if (op == '*' || op == '×') {
        value = value * right;
      } else {
        if (right == Decimal.zero) throw const AufmassException('DIVISION_BY_ZERO');
        value = AufmassRules._roundRational(value / right, AufmassRules.divisionScale);
      }
    }
    return value;
  }

  Decimal _factor() {
    final token = _take();
    if (token == '-') return -_factor();
    if (token == '(') {
      final value = expression();
      if (_take() != ')') throw const AufmassException('INVALID_FORMULA');
      return value;
    }
    if (token != null && AufmassRules._number.hasMatch(token)) {
      return Decimal.parse(token.replaceAll(',', '.'));
    }
    throw const AufmassException('INVALID_FORMULA');
  }
}

/// A deduction from a position: the position's dimensions, times count.
class AufmassDeductionInput {
  const AufmassDeductionInput({this.length, this.width, this.height, this.count});

  final Decimal? length;
  final Decimal? width;
  final Decimal? height;
  final Decimal? count;
}

class AufmassInput {
  const AufmassInput({
    required this.kind,
    this.length,
    this.width,
    this.height,
    this.count,
    this.expression,
    this.deductions = const [],
  });

  final String kind;
  final Decimal? length;
  final Decimal? width;
  final Decimal? height;
  final Decimal? count;
  final String? expression;
  final List<AufmassDeductionInput> deductions;
}

/// A rule violation; [code] is the stable code the shared vectors expect.
class AufmassException implements Exception {
  const AufmassException(this.code);

  final String code;

  @override
  String toString() => 'AufmassException($code)';
}
