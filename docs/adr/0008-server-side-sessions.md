# 0008. Server-side sessions

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, ADR 008)

## Decision
Spring Session in PostgreSQL, HttpOnly/Secure/SameSite=Lax cookies, CSRF tokens. API keys replace sessions only for the public API (release 2).

## Consequences
Sessions can be revoked immediately; JWTs in the browser were rejected.
