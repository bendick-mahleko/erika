# 0006. Self-hosted inbound Postfix

- Status: Accepted (implemented by 0024)
- Date: 2026-11-21 (Software Design v1.1, D7 / ADR 006)

## Decision
Intake addresses are served by our own Postfix (+ ClamAV) in Germany; outgoing mail uses an EU provider.

## Consequences
Intake data stays in Germany, no per-mail cost. We must operate port 25 (spike S5-a checks that Hetzner allows it).
