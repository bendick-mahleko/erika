# Field-test findings

Exit criterion: a prioritised list, with every **blocker** fixed before Stage 10. Sources: acceptance tests, telemetry, GlitchTip, weekly calls, interviews. One row per finding; link the issue or commit that fixes it.

| Priority | Meaning |
|---|---|
| Blocker | Data loss or duplication, a crash in a main flow, a wrong invoice or signed report, a security or privacy problem, or a firm can't do its daily work. Must be fixed before go-live. |
| High | Daily annoyance that costs real time, or a workaround needed. Fix before go-live if possible. |
| Medium | Noticeable, with an easy workaround. Release 1.1. |
| Low | Wish or polish. Backlog. |

| # | Priority | Found (date, source, firm/device) | Finding | Status | Fix |
|---|---|---|---|---|---|
| 1 | | | | open | |

## Metrics at the end of the field test

| Metric | Target | Result |
|---|---|---|
| Sync success (done ÷ (done + failed), offline excluded) | ≥ 99.5 % | |
| Conflicts that needed the office | as few as possible; each one reviewed | |
| Offline reports complete (A1) | 10/10 per device | |
| Robustness tests (A2–A8) | all pass on all devices | |
| Crash-free sessions (GlitchTip) | ≥ 99 % (proposal) | |
| Report time | < 5 min | |
| Signed on site | ≥ 80 % | |
