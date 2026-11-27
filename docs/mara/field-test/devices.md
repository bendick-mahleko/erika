# Reference devices (M-NF-04)

Five devices on which every acceptance test in [acceptance-tests.md](acceptance-tests.md) must pass. Requirements: Android 10+ and iOS 16+ (M-NF-04), one low-end Android, one rugged device. The rows marked "proposal" are suggestions; replace them with what the firms actually use where possible, and note the exact model and OS version used for each test run.

| # | Role | Device (proposal) | OS | RAM / storage | Why |
|---|---|---|---|---|---|
| D1 | Low-end Android | Samsung Galaxy A15 or similar | Android 14 | 4 GB / 64 GB | Slow storage and CPU: SQLCipher, PDFs and photos on weak hardware |
| D2 | Rugged Android | Samsung Galaxy XCover7 or Crosscall | Android 14 | 6 GB / 128 GB | What tradespeople carry on building sites; gloves, bright light |
| D3 | Older Android | Any device on Android 10 | Android 10 | ≥ 3 GB | The minimum OS (minSdk 29) |
| D4 | Current iPhone | iPhone 15 or 16 | iOS 18 | – | iOS reference |
| D5 | Older iPhone | iPhone SE (2nd or 3rd generation) | iOS 16 | – | Small screen, the minimum iOS |

Owned by: ___ (who keeps the devices, charged and updated).

## App size

| Build | Limit | Measured | Date |
|---|---|---|---|
| Release APK, arm64 (CI, every push) | 60 MB | 30.6 MB (local build, app 0.2.0) | 27 Nov 2026 |
| App bundle (release workflow) | 60 MB | | |
| Download size on Google Play (Console → App size) | 60 MB | | |
| TestFlight / App Store download size (App Store Connect) | 60 MB | | |

The CI check measures the arm64 release APK, which is close to what Google Play delivers to a 64-bit phone. The store figures are the authoritative ones; note them after the first upload.
