# 0001. Modular monolith with Spring Modulith

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, D2 / ADR 001)

## Context
One developer builds and runs ERIKA. Microservices would multiply deployments, monitoring and failure modes.

## Decision
One Spring Boot code base with nine modules (`intake`, `documents`, `creator`, `export`, `identity`, `billing`, `validation`, `archive`, `audit`), a shared `platform` package, the generated `contract` package and the external `invoice-core` library. Each module owns its tables and talks to others only through published interfaces or domain events. The same artefact runs as `api` or `worker` (Spring profile).

## Consequences
- `ModularityTest` (`ApplicationModules.verify()`) fails the build on boundary violations; allowed dependencies are declared in each module's `package-info.java`.
- Modules can become services later without rewriting their internals.
