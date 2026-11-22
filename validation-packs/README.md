# Validation packs

Versioned validation artefacts, loaded at runtime (ADR 0007, NF-15, BR-07). Filled in Stage 4.

Planned layout:

```
validation-packs/
  xrechnung-3.0.2-2026-08-31/     # KoSIT validator configuration (scenarios, XSD, Schematron)
    pack.yaml                    # id, source URL, checksum, activation date
    ...
  zugferd-2.4/                   # EN 16931 CII Schematron + ZUGFeRD XSD
  rule-texts/                    # German/English plain-language texts per rule ID
```

Process for a new official release: add the pack with a new version ID → CI runs the official test files against it → deploy with an activation date. Old packs stay so earlier results remain explainable.
