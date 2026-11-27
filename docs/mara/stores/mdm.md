# Private distribution through MDM (M-NF-13)

Some firms manage their phones with a mobile device management (MDM) system and want MARA installed centrally, or don't want it in the public stores. MARA supports this without a special build: the same app, the same server.

## Android: Managed Google Play

- **Public app through MDM (simplest):** MARA is in Google Play; the firm's MDM (e.g. Microsoft Intune, Miradore, Samsung Knox Manage) approves it in managed Google Play and pushes it to the work profile. Nothing for ERIKA to do.
- **Private app for one firm:** in the Play Console, under "Advanced settings → Managed Google Play", add the firm's organisation ID; or publish a private app from the firm's own managed Google Play account (only then the app is invisible to others). Same package name `de.erika.mara` is possible only for one of the two ways; we keep the public app and use the organisation targeting.
- **Managed configuration:** not needed (the server address is built in). If a firm needs another server later, add an `app_restrictions.xml` with `MARA_API_BASE_URL` (follow-up, not in the MVP).

## iOS: Apple Business Manager

- **Public app through MDM:** the firm buys free licences in Apple Business Manager (Apps and Books) and assigns MARA through its MDM; devices get it without an Apple ID.
- **Custom app for one firm:** in App Store Connect, under "Pricing and Availability", choose "Private distribution" for the firm's Apple Business Manager organisation ID. A custom app can't also be public, so this is a separate app record (e.g. `de.erika.mara.custom`) if ever needed; for the MVP we use the public app.

## What the firm must know

- Work profiles keep MARA's data apart from private data; revocation from ERIKA (`/settings/devices`) still deletes the app's data.
- The MDM must allow the camera, notifications and outbound HTTPS to the ERIKA domain.
- Backups: MARA excludes its data from cloud backups (ADR 0040); MDM backup policies don't change that.
