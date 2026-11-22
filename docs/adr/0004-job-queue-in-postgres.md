# 0004. Job queue in PostgreSQL

- Status: Accepted, refined by [0010](0010-own-job-table.md)
- Date: 2026-11-21 (Software Design v1.1, D5 / ADR 004)

## Decision
Background jobs are stored in a PostgreSQL table instead of a message broker.

## Consequences
One less system to run; throughput is ample at the planned volume (1,000 intake mails per hour).
