# 0010. Own job table instead of JobRunr

- Status: Accepted
- Date: 2026-11-22

## Context
Design D5 suggests "e.g. JobRunr". Flow A step 4 requires that a document row and its `ParseAndValidate` job are written in the same transaction. Open-source JobRunr writes jobs through its own connections and can't join the caller's transaction (a Pro feature). It also wants DDL rights at runtime, which conflicts with the restricted app role (0011).

## Decision
A small queue in `de.erika.platform.jobs`:
- table `job` (Flyway-managed); `JobQueue.enqueue()` writes through the caller's JDBC transaction;
- `JobWorker` claims due jobs with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)` and a lease;
- each handler runs in its own transaction together with its SUCCEEDED mark, recorded only while the worker still holds the lease;
- retries with exponential backoff (10 s doubling, max 1 h) up to `max_attempts`, then FAILED;
- expired leases (crashed worker) are reclaimed; the attempt counter still increases, so a crashing job can't loop forever;
- for workspace jobs the worker sets `app.workspace_id` for the handler transaction, so RLS applies.

## Consequences
- About 250 lines we own, covered by `JobWorkerIT`. No dashboard yet: failed jobs are visible via SQL and the `erika.jobs.execution` timer.
- Handlers must be idempotent (documented on `JobHandler`).
- Finished jobs accumulate; add a cleanup job (keep 30 days) when volume warrants it.
