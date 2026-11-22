# 0014. Contract-first tooling

- Status: Accepted
- Date: 2026-11-22

## Decision
- `api/openapi.yaml` (OpenAPI 3.1) is the source of truth, linted with Redocly in CI.
- Back end: `openapi-generator` (`kotlin-spring`, `interfaceOnly`) generates interfaces and models into `build/generated/openapi` before compilation. Controllers implement the interfaces, so a missing or mismatched operation fails compilation.
- Front end: `openapi-typescript` generates `src/lib/api/schema.d.ts`; `openapi-fetch` provides typed calls.
- Generated code is never committed or edited by hand.

## Consequences
Every API change starts in the YAML. On Windows the generator needs the spec as a `file:` URI (see `backend/build.gradle.kts`).
