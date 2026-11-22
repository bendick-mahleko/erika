# 0007. Validation packs as versioned data

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, D8 / ADR 007)

## Decision
KoSIT configurations, Schematron files and code lists live in `validation-packs/<version>/` and are loaded at runtime with an activation date. Every validation result stores the pack version (BR-07).

## Consequences
New official rule versions ship without an app release (NF-15); old packs stay available to explain old results.
