# 0020. The back end builds invoice-core from source

- Status: Accepted (amends 0015 and 0016)
- Date: 2026-11-22

## Context
ADR 0015 planned that the back end uses a released version of invoice-core from GitHub Packages. Since ADR 0016 the library is in the same repository. Stage 4 is the first stage where the back end uses it, in the `validation` module. Depending on a published package would mean:
- every library change needs a tag and a publish before the app can use it,
- CI and the Docker build need package credentials,
- a library change and the app change that needs it can't be tested together in one pull request.

## Decision
- The back end declares `de.erika:invoice-core:0.2.0`. `backend/settings.gradle.kts` includes `../invoice-core` as a Gradle composite build, so Gradle substitutes that module with the library built from the same commit.
- invoice-core stays an independent build: its own tests, ADRs, SemVer and release tags (`invoice-core-v*`, ADR 0016). Releases are for other consumers (the trades and Nebenkosten apps). The ERIKA back end always uses the source.
- The library's version in `invoice-core/gradle.properties` and the declared version in `backend/gradle/libs.versions.toml` are kept equal. A changed public API raises the version (SemVer), as before.
- The back-end Docker image is built with `invoice-core/` in the build context.
- Spring Modulith ignores the package `de.erika.invoicecore`: it is a library, not an application module.

## Consequences
- The back end's `./gradlew check` compiles invoice-core, but doesn't run its tests. The library's own workflow does that when files under `invoice-core/` change. A pull request touching both runs both.
- A breaking library change shows up as a compile error in the back end in the same pull request, not after a release.
