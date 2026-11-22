# 0002. Kotlin/JVM back end

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, D1 / ADR 002)

## Context
The official e-invoice tooling (Mustangproject, KoSIT validator, veraPDF) runs on the JVM.

## Decision
Kotlin on the JVM (Java 21 toolchain), Spring Boot (version: see 0009).

## Consequences
The e-invoice libraries run in-process. Node.js was rejected because it would need a separate Java service anyway.
