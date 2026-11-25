# Running ERIKA locally, step by step

This guide takes you from a fresh checkout to a running ERIKA on your own computer, and through a first look at the app. Everything stays on your machine: emails go to a local mailbox (Mailpit), files go to a local S3 store, and no real invoices are sent anywhere.

There are two ways to run it:

- **Option A – everything in Docker.** Easiest. Use this if you want to try or demo the app.
- **Option B – developer mode.** Infrastructure in Docker; back end and front end on your machine, with hot reload. Use this if you want to change code.

For deeper developer notes (tests, jobs, tenancy rules), see [dev-setup.md](dev-setup.md).

---

## 1. Install the tools

| Tool | Needed for | Check |
|---|---|---|
| Git | getting the code | `git --version` |
| Docker Desktop (Windows/macOS) or Docker Engine with Compose (Linux) | both options | `docker compose version` |
| JDK 21 | Option B | `java -version` shows 21 |
| Node.js 24 and pnpm | Option B | `node --version` shows v24; then `corepack enable` and `pnpm --version` |

On Windows, run the commands below in **Git Bash** (comes with Git). In PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

Give Docker at least 6 GB of memory: ClamAV (virus scanner), PostgreSQL and the app containers run together.

## 2. Get the code

```bash
git clone https://github.com/bendick-mahleko/erika.git
cd erika
```

All later commands start from this folder (the repository root), unless a step says `cd`.

## 3. Check that the ports are free

ERIKA uses these local ports by default:

| Port | Service |
|---|---|
| 3000 | Web app |
| 8080 | API |
| 5432 | PostgreSQL |
| 9000 | S3 store |
| 3310 | ClamAV |
| 1025 / 8025 | Mailpit (SMTP / web UI) |
| 2525 | Intake mail server (Option A only) |

If one of them is already used on your machine, move it with an environment variable when you start the stack. For example, if another Mailpit or ClamAV is running:

```bash
export ERIKA_SMTP_PORT=1026 ERIKA_MAILPIT_UI_PORT=8026 ERIKA_CLAMAV_PORT=3311
```

The other variables are `ERIKA_WEB_PORT`, `ERIKA_API_PORT`, `ERIKA_PG_PORT`, `ERIKA_S3_PORT` and `ERIKA_SMTP_INTAKE_PORT`. Remember the new numbers: the rest of this guide uses the defaults.

---

## Option A – everything in Docker

### A1. Build and start

```bash
docker compose -f infra/local/compose.yml --profile app up -d --build
```

The first run downloads the base images and builds the back end, the front end and the mail server, so it takes a while. Later runs are much faster.

### A2. Wait until it is healthy

```bash
docker compose -f infra/local/compose.yml --profile app ps
```

Wait until `postgres`, `s3`, `clamav` and `api` show **healthy**, and `worker`, `web` and `mail` show **running**. ClamAV loads its virus signatures for about a minute after it starts. Until then, uploads wait and are retried; nothing passes unscanned.

### A3. Open the app

- Web app: http://localhost:3000
- Mailbox (every email ERIKA sends): http://localhost:8025

Continue with [section 4](#4-first-steps-in-the-app).

### A4. Stop, restart, reset

```bash
docker compose -f infra/local/compose.yml --profile app stop               # stop, keep data
docker compose -f infra/local/compose.yml --profile app up -d              # start again
docker compose -f infra/local/compose.yml --profile app up -d --build      # after pulling new code
docker compose -f infra/local/compose.yml --profile app down -v            # remove everything, including all data
```

---

## Option B – developer mode

### B1. Start the infrastructure

```bash
docker compose -f infra/local/compose.yml up -d
```

This starts PostgreSQL, S3 (it also creates the buckets), ClamAV and Mailpit, but not the app itself.

### B2. Start the API (terminal 1)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=api,local'
```

The first run downloads Gradle, the dependencies and the validation packs, and builds invoice-core from source. The API sets up the database on start. It is ready when the log shows `Started` and http://localhost:8081/actuator/health answers `UP`.

### B3. Start the worker (terminal 2)

Start the worker only after the API is up:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=worker,local'
```

The worker does the background work: virus scan, reading and validating invoices, sending emails, issuing invoices, exports and the daily jobs. Without it, uploads stay "in progress", sign-up emails don't arrive and invoices stay "Wird ausgestellt …".

### B4. Start the web app (terminal 3)

```bash
cd frontend
pnpm install
pnpm dev
```

Open http://localhost:3000. Code changes in `frontend/` reload at once. For back-end changes, stop the process with Ctrl+C and run `bootRun` again.

### B5. Stop

Press Ctrl+C in the three terminals, then:

```bash
docker compose -f infra/local/compose.yml stop        # keep data
docker compose -f infra/local/compose.yml down -v     # or remove everything, including all data
```

In Option B there is no intake mail server. To test email intake, use Option A or see "Intake" in [dev-setup.md](dev-setup.md).

---

## 4. First steps in the app

The app is German first. Switch to English at any time with **EN** in the header.

### 4.1 Create an account

1. Open http://localhost:3000/signup.
2. Enter your name, company name, email (any address, e.g. `test@example.test`) and a password, which must be long. Accept the beta terms and choose **Konto anlegen**.
3. Open Mailpit (http://localhost:8025), open the email "Bitte bestätigen Sie Ihre E-Mail-Adresse" and click the link (valid for 48 hours).
4. Sign in.
5. Set up two-factor authentication: scan the QR code with an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, …) and enter the 6-digit code. Two-factor authentication is required for every account.
6. Save the recovery codes, then confirm.

### 4.2 Receive an invoice (Eingang / inbox)

1. Go to **Eingang**.
2. Upload an e-invoice. A ready-made sample is `frontend/e2e/fixtures/xrechnung-beispiel.xml`.
3. After a few seconds it appears in the list with its check result (e.g. "Gültige E-Rechnung").
4. Open it to see:
   - every field of the invoice with a readable label
   - the validation result
   - a GiroCode (QR code for banking apps) with the bank details
   - downloads of the original and a PDF view copy
   - status changes and comments

**By email (Option A only):** the Eingang page shows your workspace's intake address (`…@in.erika.local`). Send an email with the invoice attached through `localhost:2525`, for example with [swaks](https://jetmore.org/john/code/swaks/):

```bash
swaks --server localhost:2525 --to <your-address>@in.erika.local \
      --attach @frontend/e2e/fixtures/xrechnung-beispiel.xml
```

Under **Einstellungen → E-Mail-Eingang** you can allow only known senders. Email from other senders then waits in a quarantine until you import or discard it.

### 4.3 Write an invoice (Rechnungen)

1. **Einstellungen → Unternehmen:** fill in the address and the VAT ID or tax number.
2. **Einstellungen → Rechnungen:** fill in the IBAN; for XRechnung, also contact name, phone and email. Optionally add a logo and an accent colour for the PDF.
3. **Rechnungen → Neue Rechnung:** choose a customer, add lines and choose **Ausstellen**. The draft lists anything that is still missing.
4. The worker gives the invoice its number, creates the file, checks it against the official rules and archives it. You can then download it, or send it: the email appears in Mailpit.
5. On an invoice, **Als wiederkehrende Rechnung anlegen** sets up a monthly or quarterly draft.

### 4.4 Other places to look

- **Export:** a ZIP for your tax advisor (period, DATEV CSV) or a full export of your account.
- **Einstellungen → Team:** invite colleagues or a tax advisor (read-only). The invitation email arrives in Mailpit.
- **Einstellungen → Abrechnung:** billing is switched off locally, so there is no trial limit.

---

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| `port is already allocated` when starting | Another program uses the port: move it with the variables in [section 3](#3-check-that-the-ports-are-free). |
| No sign-up email | The worker must be running (Option B: terminal 2). Look in Mailpit, not your real inbox. |
| Upload stays "in progress" | ClamAV is still loading (about a minute after start) or the worker is not running. Check with `docker compose -f infra/local/compose.yml logs clamav` and the worker log. |
| Invoice stays "Wird ausgestellt …" | The worker is not running. |
| "Zu viele Versuche" after wrong passwords or codes | Wait 15 minutes, or see "Signing in locally" in [dev-setup.md](dev-setup.md). |
| Sign-up is slow or fails without internet | New passwords are checked against a public list of leaked passwords. Option B: start the API with `ERIKA_PASSWORD_BREACH_CHECK=false`. If that service can't be reached, the password is accepted anyway. |
| API stops with a message about `pg_trgm` | The database was created by an old version. Reset it with `down -v`, or see "Documents, viewer and search" in [dev-setup.md](dev-setup.md). |
| Strange errors after pulling new code | Option A: run `up -d --build`. Option B: restart S3 once (`docker compose -f infra/local/compose.yml restart s3`, then `up -d`), and if nothing else helps, reset with `down -v`. |
| `JAVA_HOME` / toolchain error in Gradle | Point `JAVA_HOME` to a JDK 21. |

To see what a container is doing:

```bash
docker compose -f infra/local/compose.yml --profile app logs -f api worker web
```

## 6. Running the tests (optional)

```bash
cd backend && ./gradlew check                    # back end: formatting and all tests (needs Docker)
cd invoice-core && ./gradlew check               # e-invoice library
cd frontend && pnpm lint && pnpm typecheck       # front end
```

To run the browser tests against a running Option A stack, first install the browser once with `pnpm exec playwright install chromium`, then:

```bash
cd frontend
E2E_BASE_URL=http://localhost:3000 E2E_EXPECT_API=true pnpm test:e2e
```

If you moved ports, also set `E2E_MAILPIT_URL` (e.g. `http://localhost:8026`) and `E2E_SMTP_PORT`.
