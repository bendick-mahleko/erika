# Privacy labels (Google Play Data safety, Apple App Privacy)

Based on what the app sends (ADR 0037–0042). The firm using MARA is the controller for job data; ERIKA processes it on their behalf. Both stores ask about data the app collects ("leaves the device"), so the answers cover everything sent to ERIKA's servers. Recheck after every change to the app's network use.

## What leaves the phone

| Data | Why | Shared with third parties? |
|---|---|---|
| Name, e-mail address (sign-in) | Account, sign-in | No |
| Work orders, reports, materials, Aufmaß, checklist answers | App functionality | No |
| Photos of the work | App functionality (report) | No |
| Customer's signature, name; customer's e-mail address for the PDF | App functionality | No |
| Working and travel times | App functionality | No |
| Device model, OS version, app version, push token | Security (device list, revocation), push "sync now" | Push token to Google FCM / Apple APNs for delivery only |
| Crash reports (type, stack trace; no content) | App diagnostics | No (own GlitchTip) |
| Daily counters of sync and upload outcomes (numbers only) | App diagnostics | No |

Not collected: location, contacts, photo library (the camera is used, the gallery isn't read), browsing, advertising ID, financial data.

## Google Play – Data safety

- Does your app collect or share any of the required user data types? **Yes.**
- Is all user data encrypted in transit? **Yes.**
- Do you provide a way for users to request that their data is deleted? **Yes** (through the employer; account closure and deletion, `docs/ops/gdpr.md`; URL `https://<domain>/datenschutz`).

| Category / type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Personal info → Name | Yes | No | Required | App functionality, Account management |
| Personal info → Email address | Yes | No | Required | App functionality, Account management |
| Photos and videos → Photos | Yes | No | Optional | App functionality |
| App activity → Other user-generated content (reports, signatures, times) | Yes | No | Required | App functionality |
| App info and performance → Crash logs | Yes | No | Required | Analytics (diagnostics) |
| App info and performance → Diagnostics | Yes | No | Required | Analytics (diagnostics) |
| Device or other IDs (push token, device ID) | Yes | No* | Required | App functionality, Fraud prevention/security |

\* Google's guidance: transfers to service providers (FCM) acting on the developer's behalf aren't "sharing".

## Apple – App Privacy

Data used to track you: **none.** Data linked to you (all "App Functionality" unless noted):

| Data type | Linked to user | Purposes |
|---|---|---|
| Contact Info → Name, Email Address | Yes | App Functionality |
| User Content → Photos or Videos | Yes | App Functionality |
| User Content → Other User Content (reports, signatures, times) | Yes | App Functionality |
| Identifiers → Device ID (push token, device record) | Yes | App Functionality |
| Diagnostics → Crash Data | No | App Functionality |
| Diagnostics → Other Diagnostic Data (sync counters) | Yes (per device) | App Functionality |

Data not collected: location, contacts, health, financial info, browsing/search history, purchases, sensitive info.

Permission texts in the app (German): camera (`NSCameraUsageDescription`: "MARA nimmt Fotos für den Arbeitsbericht auf. Sie bleiben in der App und landen nicht in Ihren Fotos."), Face ID (biometric unlock), notifications (push "sync now").
