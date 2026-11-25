# 0037. Mobile sign-in: devices, bearer tokens, 2FA at registration

- Status: Proposed (implemented in MARA Stage 3)
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

## Consequences
- Technicians type a TOTP code once per phone, not on site.
- A token lookup costs one indexed query per request. That is acceptable at MARA's scale, and it gives immediate revocation.
- Push tokens and device data are personal data: they are listed in the privacy notice (Stage 10) and deleted with the device.
