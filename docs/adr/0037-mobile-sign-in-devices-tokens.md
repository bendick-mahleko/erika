# 0037. Mobile sign-in: devices, bearer tokens, 2FA at registration

- Status: Accepted (implemented in MARA Stage 3)
- Date: 2026-11-25

## Context
ERIKA's web app uses server-side sessions with cookies and CSRF tokens (ADR 0008), and every role must use a second factor (ADR 0017). The MARA technician app runs on phones, often offline, in basements and with gloves. It needs to:
- stay signed in for weeks;
- unlock quickly;
- be locked out at once when a phone is lost (M-NF-07, design Flow F).

The MARA design required TOTP only for admins on the device.

## Decision
- **Devices.** A phone signs in once with `POST /api/v1/mobile/auth/device-login`: email, password **and TOTP, for every role**. This keeps ADR 0017. The call registers a `device` row (user, workspace, platform, app version, push token, last sync, `revoked_at`).
- **Tokens.**
  - **Format.** Opaque random tokens (256 bits). The server stores only their SHA-256, never the token. They are not JWTs, so revocation takes effect on the next request without a deny-list.
  - **Access token.** Valid 15 minutes, sent as `Authorization: Bearer`.
  - **Refresh token.** Rotates on every use (`POST /mobile/auth/refresh`). All refresh tokens of a device form one family. Using an already-used refresh token revokes the whole device, because it indicates theft.
- **Unlock on the phone.** PIN or biometrics release the SQLCipher key from the Android Keystore / iOS Keychain, and the app locks again after 5 minutes in the background. The unlock is local: the server sees only tokens.
- **Revocation.** Owners revoke devices in the web app. A revoked device gets `401 DEVICE_REVOKED`, and the app then deletes its database, files and keys.
- **Security chain.** A second Spring Security filter chain for `/api/v1/mobile/**`: stateless, bearer only, no session and no CSRF (there are no cookies to forge). `GET /api/v1/mobile/status` stays public (Stage 1).
  - The principal carries user, workspace, role and device. `TenantTransactionFilter`, `WorkspaceAccessInterceptor` (membership and closure) and `EntitlementInterceptor` work on it unchanged.
  - The workspace comes from the token, never from the request.
- **Rate limits.** Device login uses the same limits as the web sign-in. Refresh is limited per device.
- **Tests.** The mobile isolation harness, generated from `api/mobile.yaml` (`MobileContractIT`), calls every token operation with tenant B's IDs. Further tests: sign-in without TOTP is refused, refresh reuse revokes the device, a revoked device is refused at once.

## Implementation (MARA Stage 3)
- **Tables** (`V13`): `mobile_device` and `mobile_token`, both tenant tables with RLS. A bearer request arrives without a workspace, so the SECURITY DEFINER function `mobile_token_workspace(hash)` returns only the token's workspace; everything else is read with RLS in place.
- **2FA set-up in the app.** Technicians never use the web app, so they can't set up TOTP there. `POST /mobile/auth/totp-enrolment` (email and password) creates the pending secret. The next `device-login` with a code confirms it and returns the recovery codes once. `device-login` without a code answers `401 TOTP_REQUIRED`, or `409 TOTP_ENROLMENT_REQUIRED` for an account without 2FA.
- **Workspace.** The device belongs to the user's oldest membership with a role that may use the app (OWNER, MEMBER, TECHNICIAN). Tax advisors get `403 APP_NOT_FOR_ROLE`. Choosing between several firms on one phone can follow when someone needs it.
- **Role per request.** The token filter reads the current role from the membership on every request. A role change applies at once, and a removed member's phones get `401 DEVICE_REVOKED`.
- **Answers that tell the app what to do:**
  - `401 TOKEN_EXPIRED`: renew with the refresh token;
  - `401 DEVICE_REVOKED`: delete local data;
  - `401 REFRESH_TOKEN_INVALID`: sign in again (after 60 days offline) and keep the local data;
  - `426 APP_UPDATE_REQUIRED`: the app is below `erika.mobile.min-app-version`, on every MARA request except the status checks and sign-out.
- **Sign-out** in the app revokes the device (reason `SIGNED_OUT`). Owners revoke in the web app (`/settings/devices`, reason `OWNER`). A reused refresh token revokes with reason `TOKEN_REUSE`. Each revocation is audited (`identity.device.revoked`), and so is each registration (`identity.device.registered`). A new phone also triggers the existing new-device email.
- **Feature switch.** Device endpoints sit behind `erika.features.mara` like the office endpoints.
- **On the phone:**
  - The database key is created at the first sign-in. It is stored only wrapped: AES-256-GCM under a key derived from the 6-digit PIN with PBKDF2-HMAC-SHA256 (60,000 rounds, recorded with the wrapped key so later versions can raise it), in the Keystore/Keychain.
  - Simple PINs (all one digit, straight runs) are refused.
  - After 10 wrong PINs in a row the app deletes its data.
  - With biometrics on, a second copy of the key is read from the keystore only after `local_auth` recognised the user. This is an app-level gate; a hardware-bound key (`setUserAuthenticationRequired` / `kSecAccessControlBiometryCurrentSet`) is a hardening item for Stage 11.
- **App behaviour:**
  - It locks after 5 minutes in the background (short switches don't count) and checks `GET /mobile/devices/me/status` on unlock and on return to the foreground.
  - Offline, it keeps working.
  - Token renewals run one at a time, so the app never presents the same refresh token twice.

## Consequences
- Technicians type a TOTP code once per phone, not on site.
- A token lookup costs one indexed query per request. That is acceptable at MARA's scale, and it gives immediate revocation.
- Push tokens and device data are personal data: they are listed in the privacy notice (Stage 10) and deleted with the device.
