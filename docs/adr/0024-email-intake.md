# 0024. Email intake: Postfix, policy lookup and pipe to the API

- Status: Accepted (production DNS and port 25 pending spike S5-a)
- Date: 2026-11-22

## Context
IN-01 gives every workspace its own intake address. An email with a valid XRechnung attachment must be in the inbox within 2 minutes. ADR 0006 decided on our own Postfix with ClamAV in Germany. Software Design §5 describes the flow:
- Postfix accepts mail only for known tokens, through a small HTTP policy lookup;
- it limits messages to 25 MB and runs ClamAV;
- it pipes the raw message to `/internal/intake/mail`;
- the API stores the raw `.eml` write-once before anything else.

## Decision
**Address.** `<token>@<intake domain>`. The token has 20 lowercase letters and digits (about 100 bits) and is created with the workspace. Sub-addressing (`token+anything@`) and upper case are accepted. The domain is `ERIKA_INTAKE_MAIL_DOMAIN`: `in.erika-app.de` in production, `in.erika.local` locally.

**Mail container** (`infra/docker/mail.Dockerfile`, Debian 12):
- Postfix receives only. It relays nothing, has no local mailboxes, and sends no mail; outgoing mail stays with the EU provider.
- Size limit 25 MB; HELO required; VRFY off.
- STARTTLS when a certificate is mounted (Stage 7).
- **ClamAV milter**, talking to the same clamd as the worker, rejects infected mail during the SMTP dialogue. `milter_default_action=tempfail`: if the scanner is down, senders retry later.
- **Policy lookup.** `check_policy_service` runs `policy.py` (Python standard library only). It asks the API `POST /internal/intake/recipients/check` whether the recipient exists. Unknown recipients are rejected with 5.1.1 at RCPT time, so ERIKA never accepts and later bounces (no backscatter). If the API is down, the answer is a temporary failure.
- **Delivery.** A `pipe` transport runs `deliver.py` once per recipient. It posts the raw message to `POST /internal/intake/mail` and maps the HTTP answer to sysexits:
  - 202 → delivered;
  - 404 → unknown recipient (bounce);
  - 413 → too large (bounce);
  - anything else → temporary failure, and Postfix retries.

**API side.**
- The internal endpoints live outside `/api/v1`, under `/internal/intake/**`, and are documented in `api/internal.yaml`.
- The reverse proxy doesn't route them; Caddy answers 404. They are reachable only on the internal Docker network and require the shared secret `X-Intake-Secret`, compared in constant time.
- `POST /internal/intake/mail`:
  1. resolves the workspace through the SECURITY DEFINER function `intake_workspace_for_token` (the call has no workspace context);
  2. archives the raw message write-once (`message/rfc822`, ADR 0022);
  3. records an `intake` row with the envelope sender, which the IN-06 allow-list will use later;
  4. queues `intake.process-mail`.
- The worker extracts XML, PDF and ZIP attachments, also from forwarded messages, and runs them through the same pipeline as uploads (ADR 0023). An email without usable attachments is recorded as NO_ATTACHMENTS.

**Defence in depth.** The worker scans every attachment again with ClamAV, even though the milter already did.

## Consequences
- Production needs the following; build plan Stage 5, spike S5-a and Stage 7 cover them:
  - port 25 open on the Hetzner server (spike S5-a: new accounts may need to request it);
  - a reverse DNS (PTR) record for the mail host;
  - `MX in.erika-app.de → mx.erika-app.de`, plus SPF `v=spf1 -all` on `in.erika-app.de`: the domain receives only and never sends;
  - DMARC `p=reject` for the intake domain.

  DKIM matters only for outgoing mail, which comes from the EU provider's domain setup.
- A deliverability test from Gmail, Outlook and GMX must run once DNS is live. It is a manual checklist in `docs/ops/intake.md`.
- The local stack exposes the mail container on port 2525. The Playwright suite sends a real SMTP message through it, so CI covers the whole chain: Postfix → policy → milter → pipe → API → worker → inbox.
- Rotating a leaked intake address (to stop spam) is not built yet. The token can be replaced in the database; an owner-facing "new address" action can follow.
