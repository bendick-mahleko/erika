# ERIKA

**E**lektronische **R**echnungen **i**m **K**leinbetrieb **a**bwickeln — the E-Rechnung Inbox & Creator for German micro and small businesses.

Requirements and design live outside this repository (*ERIKA – Requirements Specification v1.1*, *ERIKA – Software Design v1.1*). The implementation plan is [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md); decisions are in [docs/adr/](docs/adr/README.md).

## Repository layout

| Path | Contents |
|---|---|
| `api/` | OpenAPI 3.1 contract — source of truth for back end and front end |
| `backend/` | Kotlin + Spring Boot 4 modular monolith (runs as `api` or `worker`) |
| `frontend/` | Next.js (TypeScript, Tailwind, next-intl) |
| `infra/local/` | Docker Compose for development: PostgreSQL 16, S3 (SeaweedFS), Mailpit |
| `infra/staging/` | Staging deployment: Compose, Caddy, deploy script with rollback |
| `infra/docker/` | Dockerfiles (build context: repo root) |
| `invoice-core/` | E-invoice library (Kotlin, independent Gradle build and releases) |
| `validation-packs/` | Versioned validation packs: manifests, ERIKA scenarios, German rule texts (artefacts downloaded by Gradle) |
| `docs/` | Build plan, ADRs, developer docs |

`invoice-core` (parsing, detecting and checking e-invoices; generating them from Stage 8) is a separate Gradle build in `invoice-core/`, released on its own and consumed by the back end as a versioned Maven artefact (ADR 0015, 0016). See [invoice-core/README.md](invoice-core/README.md).

## Quick start

Prerequisites: Java 21, Node 24 with pnpm, Docker.

```bash
# 1. Infrastructure
docker compose -f infra/local/compose.yml up -d

# 2. Back end: API (port 8080) and worker, in two terminals
cd backend
./gradlew bootRun --args='--spring.profiles.active=api,local'
./gradlew bootRun --args='--spring.profiles.active=worker,local'

# 3. Front end (port 3000)
cd frontend && pnpm install && pnpm dev
```

Or run everything in containers: `docker compose -f infra/local/compose.yml --profile app up -d --build`.

Open http://localhost:3000 — the page shows the system status reported by `GET /api/v1/health`.

More: [docs/dev-setup.md](docs/dev-setup.md).
