/// The sync protocol's conflict rule for one field (ADR 0038). The server decides; the app uses
/// the same rule to explain results and in its test server. Shared vectors:
/// `mara-rules/vectors/sync/field-merge.json` (Kotlin: `de.erika.sync.ConflictRules`).
enum FieldPolicy { serverWins, deviceWins, lastWriterWins }

enum FieldDecision { apply, same, keepServer, applyOver }

FieldPolicy fieldPolicyFromWire(String value) => switch (value) {
  'SERVER_WINS' => FieldPolicy.serverWins,
  'DEVICE_WINS' => FieldPolicy.deviceWins,
  'LAST_WRITER_WINS' => FieldPolicy.lastWriterWins,
  _ => throw ArgumentError('unknown policy $value'),
};

String fieldDecisionToWire(FieldDecision d) => switch (d) {
  FieldDecision.apply => 'APPLY',
  FieldDecision.same => 'SAME',
  FieldDecision.keepServer => 'KEEP_SERVER',
  FieldDecision.applyOver => 'APPLY_OVER',
};

FieldDecision decideField({
  required FieldPolicy policy,
  required int baseVersion,
  required int serverVersion,
  required bool sameValue,
}) {
  if (serverVersion <= baseVersion) return sameValue ? FieldDecision.same : FieldDecision.apply;
  if (sameValue) return FieldDecision.same;
  return policy == FieldPolicy.serverWins ? FieldDecision.keepServer : FieldDecision.applyOver;
}
