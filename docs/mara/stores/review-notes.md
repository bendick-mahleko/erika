# Notes for the store reviewers

Both stores need a working account for an app that requires sign-in. MARA needs a technician of a firm with ERIKA, sign-in with e-mail, password and an authenticator code (2FA, ADR 0037), so the reviewers get a prepared demo workspace.

## Demo workspace (production, before each submission)

1. A workspace "Demo Sanitär GmbH" (owner: the team), MARA exempt (`docs/ops/beta.md`), with 3 work orders for today and tomorrow, a checklist, articles and a customer with a fictitious address.
2. A technician account `review@<domain>` with a strong password and 2FA already set up. Reviewers can't use an authenticator app of ours, so give them the **TOTP secret** in the notes (they can add it to any authenticator) — or, if a store objects, the current recovery codes (one per review; regenerate afterwards).
3. After the review: check the demo data, reset the work orders, rotate the password, revoke the reviewer's device (`/settings/devices`).

## Text for "App access" (Google) / "Notes" (Apple)

> MARA is the field app for craftsmen of companies that use ERIKA (German invoicing software). Accounts are created by the employer; there is no public sign-up.
>
> Demo login: e-mail `review@<domain>`, password `<…>`. Two-factor authentication: add this secret to any authenticator app (TOTP, 6 digits, 30 s): `<…>`. Then choose a PIN.
>
> To try: open a work order → "Bericht" → add a time, a material and a photo → "Unterschreiben" → sign with a finger. The PDF can be shared. Everything also works in flight mode and is synchronised later.
>
> The camera is used only for job photos, which stay inside the app. No location, no tracking, no ads. Push notifications only trigger a sync and contain no data.

## Points reviewers raise, and answers

- **"Login required, no sign-up"** (Apple 5.1.1 / Google): business app for employees of a customer company; accounts come from the employer (Apple allows this for enterprise/business apps when demo credentials are provided).
- **Account deletion** (Apple 5.1.1(v)): accounts are created and removed by the employer (owner removes the member; the app deletes local data on revocation). Mention this in the notes; the privacy notice explains the employer's role.
- **Encryption export compliance:** standard HTTPS and SQLCipher; `ITSAppUsesNonExemptEncryption = NO` once confirmed.
