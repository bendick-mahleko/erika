# Demo data

`seed-demo.mjs` fills a **local or test** stack with an invented company, so you can show ERIKA without typing everything in. Never run it against production.

It uses only the public API, like a real user would. That means the virus scan, the validation, the archive, the numbering and the audit log all run as normal.

## Run it

Start the stack first (API, worker and web app; see [running-locally.md](../docs/running-locally.md)). Then, from the repository root:

```bash
node demo/seed-demo.mjs
```

It takes about a minute. Each step prints ✓ or ✗; a failed step is reported and the script goes on.

| Variable | Default | When to change it |
|---|---|---|
| `DEMO_BASE_URL` | `http://localhost:3000` | the web app runs on another port |
| `DEMO_MAILPIT` | `http://localhost:8025` | your back end sends mail to a Mailpit on another port (e.g. `http://localhost:8026` after moving ports) |
| `DEMO_EMAIL` | `jana.brandt@brandt-haustechnik.example` | to create a second demo account without resetting the database |
| `DEMO_PASSWORD` | `Demo-Zugang für Brandt Haustechnik 2026` | |

PowerShell: `$env:DEMO_MAILPIT = "http://localhost:8026"; node demo/seed-demo.mjs`

The script can run only once per email address. To start over, reset the local database (`docker compose -f infra/local/compose.yml down -v`, then start again) or set another `DEMO_EMAIL`.

## Sign in

| Account | Email | Role |
|---|---|---|
| Jana Brandt | `jana.brandt@brandt-haustechnik.example` | Owner |
| Dr. Henrike Albers | `kanzlei@steuerberatung-albers.example` | Tax advisor (read-only) |

Both use the password above. For the second factor, run:

```bash
node demo/totp.mjs
```

It prints the current 6-digit code for both accounts. Or add the secret to an authenticator app. The secrets, the `otpauth://` links and the recovery codes are in `demo/demo-account.local.json`, which Git ignores.

## What it creates

All names, addresses, tax IDs and IBANs are invented. Dates are relative to the day you run the script.

**Company:** Brandt Haustechnik GmbH, a heating and plumbing business in Leipzig, with company data, bank details, an accent colour and number ranges `RE-YYYY-NNNN` / `GS-YYYY-NNNN`.

**Incoming invoices (Eingang)**

| File | Supplier | What it shows |
|---|---|---|
| `SGN-2026-10877.xml` | Sanitär-Großhandel Nord | valid; went from checked to approved to paid; tags |
| `EF-26-3391.xml` | Elektro Fischer | valid; approved. Uploaded twice: ERIKA recognises the second copy |
| `WM-118204.xml` | Werkzeug Mayer | valid; due in 2 days (due-date reminder); note |
| `BK-2026-5512.xml` | Bürobedarf Klein | valid; 19 % and 7 % VAT in one invoice; new |
| `BW-RE-24-0981.xml` | Baustoffe Weber | **error**: total doesn't match the lines; disputed; comments from the owner and the tax advisor |
| `Werkstattrechnung-Lindner-0412.pdf` | Autohaus Lindner | plain PDF, not an e-invoice |

**Customers:** Wohnbau Petersen (XRechnung CII), Familie Keller (private customer, ZUGFeRD), Hotel Seeblick (maintenance contract), Stadt Beispielhausen (public authority, XRechnung UBL with a Leitweg-ID).

**Articles:** labour hours, call-out charge, boiler maintenance, small parts.

**Outgoing invoices (Rechnungen)**

| Number | Customer | What it shows |
|---|---|---|
| RE-…-0001 | Wohnbau Petersen | XRechnung CII; sent by email (in Mailpit) |
| RE-…-0002 | Familie Keller | ZUGFeRD PDF; sent; paid |
| RE-…-0003 | Hotel Seeblick | monthly maintenance; recurring invoice from the 1st of next month |
| RE-…-0004 | Stadt Beispielhausen | XRechnung UBL for a public authority |
| RE-…-0005 + GS-…-0001 | Hotel Seeblick | issued by mistake and cancelled |
| draft | Wohnbau Petersen | unfinished; shows what is still missing |

**Team:** the tax advisor has joined the workspace; an invitation for the colleague Tom Brandt (member) stays open.

Not included: email intake. That needs the intake mail server from Option A of the run guide (see section 4.2 there).
