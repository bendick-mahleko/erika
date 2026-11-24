# OWASP ASVS 4.0.3 Level 1: self-assessment

- Scope: the ERIKA web app and API (`frontend/`, `backend/`), the mail intake (`infra/mail`) and production configuration (`infra/production`).
- Assessed: 24 Nov 2026, Stage 11 (NF-02, MVP acceptance item 6).
- Method: requirement by requirement against code, configuration and automated tests. Supported by:
  - a ZAP baseline scan (CI job `e2e`, `.zap/rules.tsv`);
  - Trivy image scans (CI);
  - OWASP Dependency-Check and `pnpm audit` (nightly, `security.yml`);
  - the malicious-file corpus (`MaliciousFileCorpusIT`);
  - the tenant-isolation and advisor harnesses generated from the OpenAPI file (`TenantIsolationIT`, `AdvisorReadOnlyIT`).

Status: ✅ met · ⚠️ met with a documented limitation · ➖ not applicable · ❌ open.

## Result

**No open high-severity findings.** Highs found during the assessment were fixed in Stage 11 (see [Findings](#findings)). Three low-severity items remain open, with reasons. One compliance item needs the operator: encryption at rest in Hetzner Object Storage.

## V2 Authentication

| # | Requirement (short) | Status | Evidence |
|---|---|---|---|
| 2.1.1 | Passwords ≥ 12 characters | ✅ | `SignupRequest.password minLength 12`, `PasswordPolicy` |
| 2.1.2 | At least 64 characters allowed | ✅ | max 128 |
| 2.1.3 | No truncation | ✅ | Argon2id over the full string |
| 2.1.4 | Any Unicode character, including spaces | ✅ | no character rules; passphrases encouraged |
| 2.1.5 | Users can change their password | ✅ | `POST /auth/password` (Stage 11) |
| 2.1.6 | Change needs the current password | ✅ | `CURRENT_PASSWORD_WRONG`, `PasswordIT` |
| 2.1.7 | Checked against breached passwords | ✅ | k-anonymity range API, fail open; `PasswordPolicyTest` |
| 2.1.8 | Password strength meter | ⚠️ | Hint text, no meter. Breach check and length cover the purpose. Low, open (F-07) |
| 2.1.9 | No composition rules | ✅ | |
| 2.1.10 | No forced rotation | ✅ | |
| 2.1.11 | Paste and password managers allowed | ✅ | `autocomplete` attributes set |
| 2.1.12 | Option to show the password | ⚠️ | Not offered. Low, open (F-07) |
| 2.2.1 | Anti-automation | ✅ | Rate limit per IP on every sign-in step; lockout after 5 failures (ADR 0017) |
| 2.2.2 | No weak authenticators (SMS, email codes) | ✅ | TOTP only |
| 2.2.3 | Notify on credential changes | ✅ | Emails on password change, new device, lockout |
| 2.3.1 | System-generated initial passwords | ➖ | none |
| 2.5.1 | Recovery doesn't reveal the password | ✅ | Link with a token; no password sent |
| 2.5.2 | No secret questions | ✅ | |
| 2.5.3 | Recovery doesn't reveal the current password | ✅ | |
| 2.5.4 | No shared or default accounts | ✅ | |
| 2.5.6 | Secure recovery mechanism | ✅ | Random token (256 bit), SHA-256 stored, 1 h, single use; the second factor stays required (ADR 0034) |
| 2.7.x | Out-of-band authenticators | ➖ | not used |
| 2.8.1 | Time-based OTP with a defined lifetime | ✅ | 30 s steps, ±1 step, replay protection (`totp_last_step`) |

## V3 Session management

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 3.1.1 | No session ID in URLs | ✅ | cookie only |
| 3.2.1 | New session token at authentication | ✅ | `changeSessionId()` after the second factor and on workspace switch |
| 3.2.2 | ≥ 64 bits of entropy | ✅ | Spring Session random UUIDs (122 bits) |
| 3.2.3 | Tokens stored securely in the browser | ✅ | `HttpOnly`, `Secure`, `SameSite=Lax` |
| 3.3.1 | Logout invalidates the session | ✅ | server-side session deleted |
| 3.3.2 | Re-authentication or idle timeout | ✅ | 12 h idle timeout |
| 3.4.1–3.4.3 | Cookie attributes Secure, HttpOnly, SameSite | ✅ | `application.yml`; ZAP rules 10010, 10054 fail the build |
| 3.4.4 | `__Host-` prefix | ⚠️ | Cookie is `ERIKA_SESSION` (path `/`, host-only, Secure). Renaming would sign everyone out; planned for 1.1. Low (F-08) |
| 3.4.5 | Path attribute | ✅ | `/` |
| 3.7.1 | Re-check before sensitive actions | ⚠️ | Password change needs the current password. Closing an account needs the workspace name typed in, not a re-authentication. Low |

Additionally: a user's session ends at once after a password reset (every session) or change (every other session), and after removal or a role change (ADR 0032).

## V4 Access control

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 4.1.1 | Enforced server-side | ✅ | Spring Security plus `@PreAuthorize`; the UI hides only for convenience |
| 4.1.2 | Attributes can't be changed by users | ✅ | Workspace and role come from the server-side session, never from the request |
| 4.1.3 | Least privilege | ✅ | ADVISOR read-only, generated test `AdvisorReadOnlyIT`; DB role `erika_app` without DDL; RLS |
| 4.1.5 | Fails securely | ✅ | `denyAll()` default; problem details without internals |
| 4.2.1 | No IDOR | ✅ | RLS on every tenant table (`TenancyDatabaseIT`); `TenantIsolationIT` calls every operation with another tenant's IDs → 404 |
| 4.2.2 | CSRF protection | ✅ | Double-submit token for every state-changing request; webhooks use signatures or secrets |
| 4.3.1 | MFA for administrative interfaces | ✅ | 2FA for every user (NF-02). Ops UIs behind VPN or basic auth (ADR 0029) |
| 4.3.2 | No directory listing or metadata exposure | ✅ | Next.js and Spring don't list; S3 buckets private |

## V5 Validation, sanitisation and encoding

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 5.1.1–5.1.5 | Input validation, allow-lists | ✅ | Bean validation from the OpenAPI schema; enums; size limits |
| 5.2.x | Sanitisation | ✅ | No user HTML is rendered; React escapes; the Markdown component renders only our own legal texts |
| 5.3.1–5.3.3 | Output encoding | ✅ | React; JSON |
| 5.3.4 | Parameterised SQL | ✅ | `JdbcClient` named parameters throughout; dynamic SQL only from constants (`jsonRows`, filters) |
| 5.3.8 | OS command injection | ➖ | no OS commands at run time |
| 5.5.2 | XML parsers hardened (XXE) | ✅ | invoice-core (`SecurityAndRobustnessTest`) **and the KoSIT validator**: `MaliciousFileCorpusIT` sends seven XML attacks through the whole pipeline; the canary server gets no request |

## V7 Errors and logging

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 7.1.1 | No credentials or session tokens logged | ✅ | Structured logs; tokens hashed |
| 7.1.2 | No other sensitive data logged | ✅ | No invoice content in logs or exception messages (CLAUDE.md rule; review) |
| 7.4.1 | Generic error message with an ID | ✅ | RFC 9457 problem details, `X-Request-Id` |

## V8 Data protection

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 8.2.1 | No caching of sensitive data | ✅ | Spring Security `Cache-Control: no-store`; downloads `no-store, private` |
| 8.2.2 | No sensitive data in browser storage | ✅ | localStorage unused for data |
| 8.3.1 | Sensitive data not in URLs | ✅ | **Fixed (F-01):** forms now POST even without JavaScript; tokens travel in bodies, except the single-use email links |
| 8.3.4 | Inventory of sensitive data | ✅ | Record of processing activities, Verfahrensdokumentation |

## V9 Communications

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 9.1.1 | TLS for all client connections | ✅ | Caddy, automatic certificates, HSTS (1 year) |
| 9.1.2 | Current TLS configuration | ✅ | Caddy defaults: TLS 1.2+, strong ciphers |
| 9.1.3 | Only TLS 1.2 and 1.3 | ✅ | Caddy default |

## V10 Malicious code

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 10.3.2 | Integrity of downloaded artefacts | ✅ | Validation packs pinned by SHA-256; images built from pinned bases; Dependabot |

## V11 Business logic

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 11.1.1 | Steps in order | ✅ | Sign-in state machine; issuing only from DRAFT |
| 11.1.2 | Limits per user or time | ✅ | Sign-in rate limits; export queue limit; upload size limits |
| 11.1.4 | Anti-automation against bulk access | ⚠️ | No general API rate limit per session (sign-in only). Every request is authenticated and tenant-scoped. Medium for a later stage, low for go-live (F-09) |

## V12 Files and resources

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 12.1.1 | Large files refused | ✅ | 20 MB, 5,000 lines, ZIP limits |
| 12.1.2 | Compression bombs | ✅ | `ZipUnpackerTest`, `IntakeIT` |
| 12.3.1–12.3.6 | File names not used for paths; no inclusion | ✅ | Keys are `ws/<id>/<sha256>`; names only for display; zip-slip test |
| 12.4.1 | Stored outside the web root | ✅ | S3 |
| 12.4.2 | Virus scan | ✅ | ClamAV, fail closed |
| 12.5.1 | Only allowed file types | ✅ | XML, PDF, ZIP by content. **Fixed (F-02):** HTML, SVG and script roots are now refused |
| 12.5.2 | Uploaded files never executed | ✅ | Downloads `attachment`, `nosniff`, `CSP: default-src 'none'; sandbox` (corpus test with a JavaScript PDF) |
| 12.6.1 | No SSRF via user-supplied URLs | ✅ | No server-side fetch of user URLs; validator doesn't follow schema or entity URLs (corpus test) |

## V13 API

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 13.1.1 | Same encoding everywhere | ✅ | JSON UTF-8 |
| 13.1.3 | No sensitive data in API URLs | ✅ | |
| 13.2.1 | Only allowed HTTP methods | ✅ | generated interfaces |
| 13.2.3 | CSRF for cookie-authenticated APIs | ✅ | |
| 13.2.5 | Content type checked | ✅ | Spring rejects unsupported media types |

## V14 Configuration

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 14.2.1 | Components current and without known vulnerabilities | ✅ | **Fixed (F-03, F-04):** Bouncy Castle 1.83 → 1.85 (2 critical, 2 high); web image Alpine updates and npm removed (3 + 10 high). Trivy fails CI on fixable HIGH/CRITICAL |
| 14.3.2 | Debug modes off | ✅ | production profiles |
| 14.3.3 | No version leaks in headers | ✅ | `poweredByHeader: false`; ZAP 10036, 10037 fail the build |
| 14.4.1 | Content-Type with charset | ✅ | |
| 14.4.3 | Content Security Policy | ✅ | **Fixed (F-05):** nonce-based CSP on every page (`script-src 'nonce-…' 'strict-dynamic'`, `frame-ancestors 'none'`, `object-src 'none'`); ZAP 10038 fails the build; e2e checks there are no violations |
| 14.4.4 | `nosniff` | ✅ | |
| 14.4.5 | HSTS | ✅ | Caddy |
| 14.4.6 | Referrer-Policy | ✅ | `strict-origin-when-cross-origin` |
| 14.4.7 | Framing forbidden | ✅ | `X-Frame-Options: DENY` plus `frame-ancestors 'none'` |
| 14.5.3 | Strict CORS | ✅ | No CORS on the API (same origin); S3 CORS for the app origin only |

## NF-02 beyond ASVS

| Requirement | Status |
|---|---|
| TLS 1.2+ in transit | ✅ Caddy; the database connection is `sslmode=require` over the private network |
| AES-256 at rest | ✅ database volume LUKS2 (aes-xts-plain64, 512-bit key); backups pgBackRest AES-256-CBC; TOTP secrets AES-256-GCM. ⚠️ **Object storage (originals): confirm Hetzner's encryption at rest; otherwise add SSE-C or client-side encryption.** Operator item (F-06) |
| 2FA for all users | ✅ |
| Argon2 | ✅ Argon2id (19 MiB, 2 iterations) |

## Findings

| ID | Finding | Severity | Status |
|---|---|---|---|
| F-01 | Without JavaScript, login, sign-up and reset forms submitted with GET: email and password in the URL (ZAP 10024) | High | Fixed: every form uses `method="post"`; ZAP rule 10024 fails the build |
| F-02 | Uploads starting with `<` were taken as XML, so HTML and SVG with script were archived (served inertly, but not invoices) | Medium | Fixed: active-content roots refused; corpus test |
| F-03 | Bouncy Castle 1.83: CVE-2025-14813, CVE-2026-8763 (critical), CVE-2026-13506, CVE-2026-5598 (high) | Critical | Fixed: 1.85 |
| F-04 | Web image: musl and zlib (high); npm's bundled packages (10 high) | High | Fixed: `apk upgrade`, npm removed from the runtime image |
| F-05 | No Content Security Policy on the web app | Medium | Fixed: nonce-based CSP |
| F-06 | Encryption at rest of the object storage not confirmed | Medium (compliance) | **Open, operator:** ask Hetzner or add SSE-C before go-live |
| F-07 | No strength meter and no "show password" (2.1.8, 2.1.12) | Low | Open; 1.1 backlog |
| F-08 | Session cookie without `__Host-` prefix (3.4.4) | Low | Open; 1.1 backlog (rename signs everyone out) |
| F-09 | No general API rate limit per session (11.1.4) | Low | Open; revisit before release 2 (public API) |
| F-10 | No password reset or change existed (2.1.5, 2.5.6) | High | Fixed (Stage 11, ADR 0034) |
| F-11 | Availability: PostgreSQL ran as PID 1. pgBackRest's async archiver, orphaned and failing (e.g. repository unreachable), made the postmaster restart all sessions. Found by the restore drill | High | Fixed: tini as PID 1 in the PostgreSQL image; drill passes |
| F-12 | PostgreSQL image: the upstream `gosu` binary is built with an outdated Go standard library (1 critical, 25 high) | Medium | Mitigated: replaced by a `setpriv` shim, so the binary no longer exists in the container filesystem. Image scanners still list it from the base layer; revisit with the next upstream image |

Next assessment: before release 2 (public API, Peppol), or after any change to authentication or file handling.
