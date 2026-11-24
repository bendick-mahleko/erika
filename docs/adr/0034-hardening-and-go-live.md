# 0034. Hardening and go-live

- Status: Accepted
- Date: 2026-11-24

## Context
Stage 11 has to tick the MVP acceptance list (Requirements §8). Two kinds of work are involved:
- **engineering:** OWASP ASVS L1, a ZAP baseline in CI, the malicious-file corpus, dependency scans, a load test, accessibility;
- **people:** a lawyer, a tax advisor, usability participants, a restore drill in production-like conditions.

The self-assessment found gaps that had to be closed before launch. The most important: users had no way to reset or change their password.

## Decision

### Passwords (ASVS 2.1.5–2.1.7, 2.5)
- **Reset.** `POST /auth/password-reset` always answers 202 and emails a link valid for one hour and usable once; only the token's SHA-256 is stored (`user_token`, purpose PASSWORD_RESET). `POST /auth/password-reset/confirm` sets the new password.
  - A reset ends **every** session of the user.
  - The second factor stays required: a reset is not a way around TOTP.
  - A refused password rolls the transaction back, so the token stays usable.
- **Change.** `POST /auth/password` (any role, it is the user's own account) needs the current password and ends every other session.
- **Breached passwords.** New passwords are checked against the Pwned Passwords range API (k-anonymity: only the first 5 hex characters of the SHA-1 leave, padded answers).
  - If the service can't be reached, the password is accepted (fail open): sign-up must not depend on a third party, and the other rules still apply.
  - Configurable (`erika.passwords.breach-check`); off in tests, covered by `PasswordPolicyTest` with a fake server.
- **Confirmation.** Both reset and change are audited and confirmed by email.

### Content Security Policy (ASVS 14.4.3)
- **Nonce.** The Next.js proxy sets a CSP with a fresh nonce per request: `script-src 'self' 'nonce-…' 'strict-dynamic'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`. Every page is rendered per request (the layout reads the session), so Next.js puts the nonce on its own scripts.
- **Styles** keep `'unsafe-inline'`: React sets style attributes. No external styles are loaded.
- **Uploads.** `connect-src` adds `ERIKA_CSP_CONNECT_SRC`: the object store the browser uploads to with pre-signed URLs.
- **More headers:** `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy: same-origin`.

### Forms always POST
Every form has `method="post"`. Without JavaScript the browser would otherwise submit with GET, putting email and password in the URL and the server logs (found by ZAP rule 10024).

### Files
- **Active web content refused.** An upload that starts with `<` counts as XML only when its document element is not HTML, SVG or script. Downloads were already inert (attachment, `nosniff`, sandbox CSP); this keeps active content out of the archive altogether.
- **`MaliciousFileCorpusIT`** sends seven XML attacks through the whole pipeline (upload, ClamAV, parser, KoSIT, veraPDF): external and parameter entities, external DTD, local file, billion laughs, XInclude, remote schema location. A canary HTTP server proves that neither the parser nor **the validator** ever reaches out. It also covers HTML and SVG with script, EICAR in a ZIP, ZIPs three levels deep, and a PDF with JavaScript (archived and only ever downloaded).

### Scans in CI (job `e2e`, against the running stack)
- **ZAP baseline.** `.zap/rules.tsv` fails the build on the rules that matter: missing CSP or security headers, cookies without HttpOnly or SameSite, secrets in URLs, error or version disclosure. Accepted warnings are listed there with their reason.
- **Trivy** scans the three images and fails on HIGH or CRITICAL vulnerabilities that have a fix.
- **Nightly** (unchanged): OWASP Dependency-Check and `pnpm audit`. Dependabot weekly.
- **Fixes from the first run:** Bouncy Castle 1.83 → 1.85. In the web image, `apk upgrade` and npm removed (it isn't needed at run time).

### PostgreSQL image
- **tini as PID 1.** The restore drill found that pgBackRest's asynchronous archive-push leaves an orphaned background process. With PostgreSQL as PID 1, that orphan became a child of the postmaster, and its non-zero exit (repository unreachable, or no stanza yet) made PostgreSQL restart every session. Production would have restarted repeatedly during an object-storage outage. With tini the orphan is reaped harmlessly.
- **`gosu` replaced.** It is a Go binary with an outdated standard library. A `setpriv` shim (util-linux) takes its place.

### Load test
`loadtest/` holds k6 scenarios and a seed:
- 50 concurrent users on a workspace with 10,000 documents, with thresholds from NF-07 and AR-04;
- 1,000 intake emails per hour through the internal intake endpoint;
- `prepare.mjs` creates the account through the API.

Results are recorded in `docs/ops/load-test-results.md`. The decisive run is on staging.

### Landing page and launch
- **Landing page.** `/` is the public landing page; signed-in users go to the inbox, and the system status moved to `/systemstatus`. The page reads the sign-up policy: during the closed beta it offers "Beta-Zugang anfragen", afterwards "14 Tage kostenlos testen".
- **Launch is configuration only:** sign-up mode, billing, price text (`ERIKA_PRICE_TEXT`). See `docs/ops/go-live.md`.

### Tasks that need people
Protocols prepared:
- usability test (NF-12, CR-05): `docs/testing/usability-nf12.md`;
- manual accessibility check (NF-11): `docs/testing/accessibility-manual.md`;
- lawyer brief with the GoBD wording question: `docs/legal/lawyer-review.md`;
- tax-advisor review and DATEV import: `docs/legal/tax-advisor-review.md`;
- beta triage: `docs/ops/beta-triage.md`;
- the go-live checklist: `docs/ops/go-live.md`.

The traceability matrix (`docs/testing/traceability.md`) shows what is automated and what is still open.

## Consequences
- **Breach check.** Sign-up and password changes make one outbound HTTPS call (a hash prefix, no personal data). The privacy notice should mention it (lawyer brief).
- **CSP upkeep.** A new origin the browser talks to (e.g. a CDN) must be added to the CSP. The e2e test fails on CSP violations.
- **New tests** queue more background jobs. Tests that run the worker must drain the queue, not run one batch (`MailDeliveryIT`).
- **Open low-severity ASVS items** (strength meter and "show password", `__Host-` cookie, a general API rate limit) are in the 1.1 backlog. F-06 (encryption at rest of the object storage) needs the operator before go-live.
