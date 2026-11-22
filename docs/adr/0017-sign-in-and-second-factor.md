# 0017. Sign-in, second factor and sessions

- Status: Accepted
- Date: 2026-11-22

## Context
Stage 3 builds sign-up and sign-in (EX-02 core). The design (§7) asks for Argon2id, TOTP "mandatory for owners and tax advisors", server-side sessions with HttpOnly/Secure/SameSite=Lax cookies, CSRF tokens, a 12 h idle timeout, login rate limiting with lockout, and a notification email on sign-in from a new device. The requirements (NF-02) say "2FA (TOTP) for all users".

## Decision
- **2FA for every role.** NF-02 is stricter than the design, and the requirements win. After the password, a user without 2FA must set it up before getting a session. Members are rare in the target group (0–20 staff), so the extra step costs little.
- **Two steps in one server-side session.** `POST /auth/login` checks the password and stores a *pending* sign-in (user, workspace, role) in the session, valid for 10 minutes. It grants nothing except the second-factor endpoints. `POST /auth/totp` (code or recovery code) or `POST /auth/totp/enrolment/confirm` then put the Spring Security principal into the session. The session ID changes at both steps (no session fixation).
- **Sessions:** Spring Session JDBC (ADR 0008), cookie `ERIKA_SESSION` (HttpOnly, Secure, SameSite=Lax), 12 h idle timeout. The principal's name in `spring_session.principal_name` is the user ID, so a user's sessions can be found and ended later (Stage 9: role change, removal).
- **CSRF:** Spring Security's SPA mode. The `XSRF-TOKEN` cookie (readable by JavaScript) is echoed in `X-XSRF-TOKEN`. `GET /auth/session` sets the cookie.
- **Passwords:** Argon2id with the OWASP minimum parameters (19 MiB, 2 iterations, parallelism 1), at least 12 characters, and the password must not contain the email address or workspace name. Unknown user, wrong password and locked account all answer 401 `INVALID_CREDENTIALS`, with the same hashing work. Sign-up always answers 202; an address that is already registered gets a notice email instead. This avoids account enumeration.
- **TOTP:** RFC 6238 (SHA-1, 6 digits, 30 s, ±1 step), our own implementation of about 40 lines, tested against the RFC vectors. Each time step is accepted once per user (`totp_last_step`, updated atomically). The secrets are encrypted with AES-256-GCM, using a key from configuration (`ERIKA_TOTP_ENCRYPTION_KEY`), so a database dump alone doesn't reveal second factors.
- **Recovery codes:** 10 single-use codes, about 60 bits each, shown once. Only their SHA-256 is stored: random codes don't need a slow hash.
- **Lockout:** 5 failed attempts in a row (password or second factor) lock the account for 15 minutes and send an email. The counter is reset only by a complete sign-in. Failures are committed in their own transaction.
- **Rate limit:** 20 sign-in attempts per client IP per 5 minutes, in memory, per API instance. It is a first line of defence; the lockout in the database is the real limit.
- **New device:** a random device cookie (`erika_device`, HttpOnly, 400 days). Only its hash is stored in `known_device`. A sign-in from an unknown device sends an email, except for the very first device.
- **Workspace choice:** a user signs in to their oldest membership. Choosing between several workspaces (tax advisors) comes with invitations in Stage 9.

## Consequences
- Losing `ERIKA_TOTP_ENCRYPTION_KEY` means every user must set up 2FA again. The key is kept in SOPS with the other secrets, and a key rotation needs a re-encryption job (not built yet).
- The rate limit is per instance. With several API instances behind a load balancer it multiplies; the lockout still applies.
- Passkeys (WebAuthn) as a second factor stay a later option (design §7).
