# 0016. invoice-core lives in this repository

- Status: Accepted (amends 0015; amended by 0020)
- Date: 2026-11-22

## Context
Stage 2 created `invoice-core` as a separate repository. The owner decided to keep all code in one GitHub repository (`bendick-mahleko/erika`).

## Decision
- `invoice-core` moved to the folder `invoice-core/`, with its history (subtree merge).
- It stays an independent build: own Gradle wrapper and settings, own ADRs (`invoice-core/docs/adr`), own workflow (`.github/workflows/invoice-core.yml`, runs only on changes under `invoice-core/`).
- Releases are tagged `invoice-core-vX.Y.Z` and published to the GitHub Packages registry of this repository. Plain `v*` tags stay reserved for the app.
- The back end still depends on a fixed released version (ADR 0015), not on the source folder, so the library can be shared with the other apps later.

## Consequences
- One clone gives app and library; a library change and the app change that uses it can be reviewed in one pull request, but the app only sees it after a library release.
- Docker builds ignore `invoice-core/`; the back-end image gets the library as a Maven dependency.
