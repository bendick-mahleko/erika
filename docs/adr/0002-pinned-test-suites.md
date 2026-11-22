# 0002. Official test suites pinned by checksum, downloaded by the build

- Status: Accepted
- Date: 2026-11-22

## Decision
The KoSIT XRechnung test suite, the CEN EN 16931 examples and the ZUGFeRD corpus are not committed. `build.gradle.kts` lists each archive with its URL and SHA-256; `downloadTestSuites` fetches them once into `~/.gradle/caches/erika-test-suites`, verifies the checksum and unpacks them into `build/test-suites`. `test` depends on it.

Parsed models of the official files are kept as reviewed golden files in `src/test/resources/golden`.

## Consequences
- Reproducible: a changed or tampered archive fails the build.
- Updating a suite = change URL + checksum, run `./gradlew test -PupdateGolden`, review the diff.
- CI needs network access on the first run (cached afterwards).
