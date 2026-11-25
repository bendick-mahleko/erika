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

On Windows you can use **Git Bash** (comes with Git) or **PowerShell**. Most commands are the same in both. Where they differ, this guide shows a `powershell` block after the `bash` one. The main differences:

| | Bash (Git Bash, macOS, Linux) | PowerShell |
|---|---|---|
| set a variable for this terminal | `export NAME=value` | `$env:NAME = "value"` |
| set a variable for one command | `NAME=value command` | `$env:NAME = "value"; command` (stays set afterwards) |
| run Gradle | `./gradlew` | `.\gradlew` |
| Gradle arguments with commas | `--args='--a=b,c'` | `"--args=--a=b,c"` (double quotes around the whole argument) |
| chain commands | `cd backend && ./gradlew check` | `cd backend; .\gradlew check` (Windows PowerShell 5.1 has no `&&`) |
| filter output | `… \| grep 1025` | `… \| Select-String 1025` |

Variables only last for the terminal you set them in. Set them again in every new window.

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

If a port is already in use, `docker compose up` stops with an error such as:

```
Bind for 127.0.0.1:1025 failed: port is already allocated
```

### 3.1 Find out what uses the port

Often it is a container from another project (for example another Mailpit or ClamAV):

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | grep 1025
```

```powershell
docker ps --format '{{.Names}}\t{{.Ports}}' | Select-String 1025
```

If nothing shows up, a program outside Docker holds the port: `netstat -ano | findstr :1025` on Windows, `lsof -i :1025` on macOS and Linux.

You have two choices: stop the other program (for a container: `docker stop <name>`), or give ERIKA other ports.

### 3.2 Give ERIKA other ports

Set the variable for each busy port before you start the stack. For example, if another project already runs Mailpit and ClamAV:

```bash
export ERIKA_SMTP_PORT=1026 ERIKA_MAILPIT_UI_PORT=8026 ERIKA_CLAMAV_PORT=3311
docker compose -f infra/local/compose.yml up -d
```

```powershell
$env:ERIKA_SMTP_PORT = "1026"; $env:ERIKA_MAILPIT_UI_PORT = "8026"; $env:ERIKA_CLAMAV_PORT = "3311"
docker compose -f infra/local/compose.yml up -d
```

| Variable | Default | Service |
|---|---|---|
| `ERIKA_WEB_PORT` | 3000 | Web app |
| `ERIKA_API_PORT` | 8080 | API |
| `ERIKA_PG_PORT` | 5432 | PostgreSQL |
| `ERIKA_S3_PORT` | 9000 | S3 store |
| `ERIKA_CLAMAV_PORT` | 3310 | ClamAV |
| `ERIKA_SMTP_PORT` | 1025 | Mailpit SMTP |
| `ERIKA_MAILPIT_UI_PORT` | 8025 | Mailpit web UI |
| `ERIKA_SMTP_INTAKE_PORT` | 2525 | Intake mail server (Option A) |

Things to know:

- **Variables only last for the current terminal.** Set the variables again in every new terminal, and every time you run `docker compose` for ERIKA. Otherwise Compose tries the default ports again.
- **Use the new numbers everywhere.** The rest of this guide uses the defaults. With the example above, Mailpit is at http://localhost:8026 instead of http://localhost:8025.
- **Option A:** the API, worker and web containers reach Mailpit, ClamAV, PostgreSQL and S3 inside Docker's network. Moving the ports on your machine doesn't affect them.
- **Option B:** the API and worker run on your machine, so they must use the moved ports too:
  - `ERIKA_SMTP_PORT` and `ERIKA_CLAMAV_PORT` are read by the back end as well. Set them in terminals 1 and 2 before `bootRun`.
  - If you moved PostgreSQL or S3, also set `ERIKA_DB_URL=jdbc:postgresql://localhost:<port>/erika?currentSchema=erika`, and set `ERIKA_S3_ENDPOINT` and `ERIKA_S3_PUBLIC_ENDPOINT` to `http://localhost:<port>`.
- **Browser tests:** set `E2E_MAILPIT_URL` to the moved Mailpit web UI, e.g. `http://localhost:8026` (see [dev-setup.md](dev-setup.md)).

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

If you ran Option A before, stop its app containers first. Otherwise they keep ports 8080 and 3000, and the Docker worker takes jobs away from yours:

```bash
docker compose -f infra/local/compose.yml --profile app stop api worker web mail
```

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

```powershell
cd backend
.\gradlew bootRun "--args=--spring.profiles.active=api,local"
```

The first run downloads Gradle, the dependencies and the validation packs, and builds invoice-core from source. The API sets up the database on start. It is ready when the log shows `Started` and http://localhost:8081/actuator/health answers `UP`.

Write the profiles without a space (`api,local`, not `api, local`). If you moved Mailpit or ClamAV in [section 3.2](#32-give-erika-other-ports), set `ERIKA_SMTP_PORT` and `ERIKA_CLAMAV_PORT` in this terminal (and in the worker's) before `bootRun`.

### B3. Start the worker (terminal 2)

Start the worker only after the API is up:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=worker,local'
```

```powershell
cd backend
.\gradlew bootRun "--args=--spring.profiles.active=worker,local"
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

swaks needs Perl. On Windows, run it in Git Bash or WSL, or send the email from any mail program set up with the SMTP server `localhost`, port `2525`, without authentication.

Under **Einstellungen → E-Mail-Eingang** you can allow only known senders. Email from other senders then waits in a quarantine until you import or discard it.

### 4.3 Write an invoice (Rechnungen)

1. **Einstellungen → Unternehmen:** fill in the address and the VAT ID or tax number.
2. **Einstellungen → Rechnungen:** fill in the IBAN; for XRechnung, also contact name, phone and email. Optionally add a logo and an accent colour for the PDF.
3. **Rechnungen → Neue Rechnung:** choose a customer, add lines and choose **Ausstellen**. The draft lists anything that is still missing.
4. The worker gives the invoice its number, creates the file, checks it against the official rules and archives it. You can then download it, or send it: the email appears in Mailpit.
5. On an invoice, **Als wiederkehrende Rechnung anlegen** sets up a monthly or quarterly draft.

### 4.4 Demo data

For a demo, you don't have to type everything in. With the stack running (API, worker and web app):

```bash
node demo/seed-demo.mjs
```

It creates the invented company *Brandt Haustechnik GmbH*: an owner and a tax advisor account, supplier invoices in several states (one with an error), customers, articles, issued and sent invoices, a cancellation, a recurring invoice and an open draft. Details and sign-in: [demo/README.md](../demo/README.md).

### 4.5 Other places to look

- **Export:** a ZIP for your tax advisor (period, DATEV CSV) or a full export of your account.
- **Einstellungen → Team:** invite colleagues or a tax advisor (read-only). The invitation email arrives in Mailpit.
- **Einstellungen → Abrechnung:** billing is switched off locally, so there is no trial limit.

---

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| `port is already allocated` when starting | Another program or another project's container uses the port. Find it and stop it, or move ERIKA's port ([section 3](#3-check-that-the-ports-are-free)). In Option B, also give the back end the new ports. |
| http://localhost:3000 doesn't open | Nothing serves the web app. Option B: start it in a third terminal (`cd frontend`, `pnpm install`, `pnpm dev`). Option A: check `docker compose -f infra/local/compose.yml --profile app ps`. |
| No sign-up email | The worker must be running (Option B: terminal 2). Look in Mailpit, not your real inbox. If another project's Mailpit uses port 1025 and you didn't move ERIKA's ports, the email is in that Mailpit (usually http://localhost:8025). |
| Upload stays "in progress" | ClamAV is still loading (about a minute after start) or the worker is not running. Check with `docker compose -f infra/local/compose.yml logs clamav` and the worker log. |
| Invoice stays "Wird ausgestellt …" | The worker is not running. |
| "Zu viele Versuche" after wrong passwords or codes | Wait 15 minutes, or see "Signing in locally" in [dev-setup.md](dev-setup.md). |
| Sign-up is slow or fails without internet | New passwords are checked against a public list of leaked passwords. Option B: start the API with `ERIKA_PASSWORD_BREACH_CHECK=false` (PowerShell: `$env:ERIKA_PASSWORD_BREACH_CHECK = "false"` before `bootRun`). If that service can't be reached, the password is accepted anyway. |
| API stops with a message about `pg_trgm` | The database was created by an old version. Reset it with `down -v`, or see "Documents, viewer and search" in [dev-setup.md](dev-setup.md). |
| Strange errors after pulling new code | Option A: run `up -d --build`. Option B: restart S3 once (`docker compose -f infra/local/compose.yml restart s3`, then `up -d`), and if nothing else helps, reset with `down -v`. |
| `JAVA_HOME` / toolchain error in Gradle | Point `JAVA_HOME` to a JDK 21. |
| `Invalid empty profile` when starting the API or worker | There is a space in the profile list (`api, local`). Write `api,local`. In PowerShell, quote the whole argument: `"--args=--spring.profiles.active=api,local"`. |
| API in Option B: `Port 8080 was already in use`; web app: port 3000 in use; jobs run but not in your worker's log | The Option A containers are still running. Stop them: `docker compose -f infra/local/compose.yml --profile app stop api worker web mail`. |
| Mailpit or ClamAV not reachable, `docker compose ps` shows no `127.0.0.1:…` port for them | The stack was started again without the port variables, and the default ports are taken. Set the variables in this terminal ([section 3.2](#32-give-erika-other-ports)) and run `up -d` again. |
| PowerShell: `export` or `./gradlew` is not recognised | Use the PowerShell forms: `$env:NAME = "value"` and `.\gradlew` (see the table in [section 1](#1-install-the-tools)). |

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

```powershell
cd backend; .\gradlew check; cd ..              # back end
cd invoice-core; .\gradlew check; cd ..         # e-invoice library
cd frontend; pnpm lint; pnpm typecheck; cd ..    # front end
```

To run the browser tests against a running Option A stack, first install the browser once with `pnpm exec playwright install chromium`, then:

```bash
cd frontend
E2E_BASE_URL=http://localhost:3000 E2E_EXPECT_API=true pnpm test:e2e
```

```powershell
cd frontend
$env:E2E_BASE_URL = "http://localhost:3000"; $env:E2E_EXPECT_API = "true"
pnpm test:e2e
```

If you moved ports, also set `E2E_MAILPIT_URL` (e.g. `http://localhost:8026`) and `E2E_SMTP_PORT`.
