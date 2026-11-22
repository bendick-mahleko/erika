# 0009. Spring Boot 4 instead of Spring Boot 3

- Status: Accepted
- Date: 2026-11-22

## Context
Software Design D1 names "Spring Boot 3". When implementation started (November 2026) the current release was Spring Boot 4.2, and open-source support for the 3.x line had ended (3.5: June 2026). A product that must run until at least 2035 would otherwise start on an unsupported framework and face a migration within months.

## Decision
Spring Boot 4.2, Spring Modulith 2.2, Kotlin 2.4, Java 21.

## Consequences
- Jackson 3 (`tools.jackson.*`). Some Jackson 2 settings no longer exist (e.g. `write-dates-as-timestamps`; ISO 8601 is now the default).
- Starters are modular (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`).
- The design intent (Kotlin + Spring, JVM libraries in-process) is unchanged.
