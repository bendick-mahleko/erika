# Load tests (NF-07, ADR 0034)

Targets from the requirements and the build plan:
- 50 concurrent users;
- 10,000 documents per workspace;
- 1,000 intake emails per hour;
- viewer under 2 s and search under 1 s (95th percentile).

**Run against a test stack only**: the local Compose stack or staging, never production. The scripts create accounts and data.

## 1. Prepare (local stack)

```sh
docker compose -f infra/local/compose.yml --profile app up -d --build
node loadtest/prepare.mjs > loadtest/.session.json     # LT_BASE_URL, LT_MAILPIT if not default
WS=$(node -p "require('./loadtest/.session.json').workspaceId")
docker compose -f infra/local/compose.yml exec -T postgres \
  psql -U erika_owner -d erika -v ws="$WS" -v n=10000 -f - < loadtest/seed.sql
```

`.session.json` holds a live session cookie. It is ignored by Git; delete it afterwards.

## 2. Run

k6 via Docker, so nothing needs installing. On Linux, use `--network host` and `localhost` instead of `host.docker.internal`.

```sh
docker run --rm -i -v "$PWD/loadtest:/lt" -v "$PWD/frontend/e2e/fixtures:/frontend/e2e/fixtures:ro" -w /lt \
  grafana/k6:1.3.0 run -e LT_BASE_URL=http://host.docker.internal:3000 browse.js
docker run --rm -i -v "$PWD/loadtest:/lt" -v "$PWD/frontend/e2e/fixtures:/frontend/e2e/fixtures:ro" -w /lt \
  grafana/k6:1.3.0 run -e LT_API_URL=http://host.docker.internal:8080 -e LT_INTAKE_SECRET=dev-intake-secret intake.js
```

Options:
- `LT_VUS` (default 50) and `LT_DURATION` (default 5m) for `browse.js`;
- `LT_MAILS_PER_HOUR` (default 1000) and `LT_DURATION` (default 10m) for `intake.js`.

The thresholds in the scripts are the acceptance criteria: k6 exits non-zero if one is missed.

## 3. Did the worker keep up?

During and after `intake.js`, the queue must not grow without bound and every mail must be processed:

```sql
SELECT type, state, count(*) FROM erika.job WHERE created_at > now() - interval '1 hour' GROUP BY 1, 2 ORDER BY 1, 2;
SELECT count(*) FILTER (WHERE processing = 'DONE') AS done, count(*) AS total
  FROM erika.document WHERE workspace_id = '<ws>' AND received_via = 'EMAIL';
```

The Grafana dashboard (staging/production) shows the same in "Job queue" (ADR 0029).

## Results

Record every run in [docs/ops/load-test-results.md](../docs/ops/load-test-results.md): environment, date, numbers, verdict.
