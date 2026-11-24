# Load-test results (NF-07, AR-04)

Scripts and procedure: [loadtest/README.md](../../loadtest/README.md). Thresholds are the acceptance criteria. The run that counts for go-live is on **staging** (production-like servers); local runs show the software has no bottleneck.

## 24 Nov 2026: local Compose stack (Stage 11)

Environment: developer laptop (Windows, Docker Desktop, 19 GB for Docker). All services on one machine: API, worker, PostgreSQL, SeaweedFS, ClamAV. Data: one workspace with **10,001 documents** (seed plus one real upload as the view template).

**Users: `browse.js`, 50 concurrent users, 5 minutes.** Each iteration: inbox list, search, filtered list, open one invoice (details and view).

| Metric | Threshold | Result |
|---|---|---|
| Inbox list, p95 | < 1 s | **46 ms** (median 18 ms) |
| Search over 10,000 invoices, p95 | < 1 s (AR-04) | **80 ms** (median 22 ms) |
| Open invoice (details + view), p95 | < 2 s (NF-07) | **73 ms** (median 31 ms) |
| Failed requests | < 1 % | **0.01 %** (1 of ≈ 10,000) |

One document request took 30 s and failed: a single outlier, not repeated. Watch for it in the staging run.

**Intake: `intake.js`, 1,000 emails per hour, 10 minutes.** XRechnung attachment, delivered through the internal intake endpoint as Postfix does.

| Metric | Threshold | Result |
|---|---|---|
| Mails sent | 167 (1,000/h × 10 min) | 167 |
| Acceptance time, p95 | < 2 s | **89 ms** |
| Failed deliveries | < 1 % | **0 %** |
| Processed by the worker (virus scan, archive, parse, validate) | all, queue empty afterwards | **167 of 167**; no job queued or failed one minute after the run |

**Verdict:** passed locally.

## Staging run (open)

| Date | Users / duration | List p95 | Search p95 | View p95 | Errors | Intake processed | Verdict |
|---|---|---|---|---|---|---|---|
| | 50 / 15 min | | | | | | |
