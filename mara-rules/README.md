# mara-rules: shared rules for the MARA app and the back end

Some MARA rules run twice: on the phone (Dart, offline) and on the server (Kotlin). Both must give exactly the same result, or a signed report's hash or an invoice total won't match (ADR 0036, MARA build plan §2).

This folder is the single source for those rules' **test vectors**. It holds no code. The Kotlin tests in `backend/` and the Dart tests in `mobile/` read the same files. A change to a vector fails both builds until both implementations agree.

| Rule | Vectors | Kotlin | Dart | Since |
|---|---|---|---|---|
| Aufmaß: area (length × width) | [vectors/aufmass/area.json](vectors/aufmass/area.json) | `de.erika.reports.AufmassRules` | `mobile/lib/domain/rules/aufmass.dart` | MARA Stage 1 |
| Aufmaß: positions (length, area, volume, pieces, count, deductions) | [vectors/aufmass/position.json](vectors/aufmass/position.json) | `de.erika.reports.AufmassRules` | `mobile/lib/domain/rules/aufmass.dart` | MARA Stage 5 |
| Aufmaß: simple formulas | [vectors/aufmass/formula.json](vectors/aufmass/formula.json) | `de.erika.reports.AufmassRules` | `mobile/lib/domain/rules/aufmass.dart` | MARA Stage 5 |
| IDs two phones derive alike (report, checklist answer) | [vectors/ids/derived.json](vectors/ids/derived.json) | `de.erika.reports.DerivedIds` | `mobile/lib/domain/derived_id.dart` | MARA Stage 5 |
| Sync: deletes and edits of deleted records | [vectors/sync/delete.json](vectors/sync/delete.json), [vectors/sync/edit-deleted.json](vectors/sync/edit-deleted.json) | `de.erika.sync.ConflictRules` | `mobile/lib/domain/rules/conflict_rules.dart` | MARA Stage 5 |
| Sync: field merge (who wins a concurrent change) | [vectors/sync/field-merge.json](vectors/sync/field-merge.json) | `de.erika.sync.ConflictRules` | `mobile/lib/domain/rules/conflict_rules.dart` | MARA Stage 4 |

Planned: the canonical report JSON and its SHA-256 (Stage 7), and invoice lines with rounding to the billing unit (Stage 8).

## Vector format

One JSON file per rule:

```json
{
  "rule": "aufmass.area",
  "version": 1,
  "description": "What the rule does, including rounding.",
  "cases": [
    { "name": "typical room", "input": { "length": "4.25", "width": "3.10" }, "expected": { "result": "13.175" } },
    { "name": "negative length is refused", "input": { "length": "-1", "width": "2" }, "expected": { "error": "NEGATIVE_DIMENSION" } }
  ]
}
```

- **Numbers are decimal strings**, never JSON numbers, so no side reads them as binary floating point. Results are compared as strings, including trailing zeros (`"12.000"`).
- **`expected`** has either `result` or `error`. `error` is the stable code the implementation must raise.
- **`version`** goes up when the rule changes in a way that changes results. Old reports keep the version they were calculated with.
- Every case has a `name` that says what it checks; test reports show it.

## Adding or changing a rule

1. Write or change the vector file first.
2. Change the Kotlin and the Dart implementation until both test suites pass (`./gradlew test --tests '*VectorsTest'` in `backend/`, `flutter test` in `mobile/`).
3. Add the rule to the table above.
