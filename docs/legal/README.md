# Legal and compliance: status

Everything in this folder and in `frontend/src/content/legal/` is a **draft written by the development team, not legal advice**. Before the beta, the operator must fill in the placeholders ([in square brackets]) and decide which texts need a lawyer now. A full lawyer's review is planned for Stage 11 (NF-09).

## Data-processing agreements with sub-processors (NF-01)

| Sub-processor | Service | Location | Data-processing agreement | Status |
|---|---|---|---|---|
| Hetzner Online GmbH, Gunzenhausen | Servers, volumes, object storage (projects erika-prod and erika-backup) | Nuremberg, Falkenstein (DE) | Concluded online in the Hetzner account (Cloud Console → account → "Auftragsverarbeitung"); download the PDF and keep it with the contract records | ☐ to do |
| Outgoing mail provider (EU, still to choose) | System emails | EU | From the provider's dashboard or terms | ☐ provider not chosen |
| Stripe Payments Europe Ltd. | Payments: subscriptions, card and SEPA data (implemented in Stage 10, off during the beta) | EU (Ireland); Stripe may transfer data to the US (SCCs) | Stripe's data-processing agreement (accepted in the Stripe account); before billing goes live, add Stripe to `/datenschutz`, `/unterauftragsverarbeiter` and the record of processing activities | ☐ before go-live of billing |

| Google Ireland Ltd. (Firebase Cloud Messaging), MARA only | Push messages to the MARA app ("please sync"), also to iPhones through APNs | EU; transfer to the US possible (EU-U.S. Data Privacy Framework, SCCs) | Firebase data-processing terms (accepted in the Firebase console) | ☐ before MARA push goes live |
| Apple Distribution International Ltd. (APNs), MARA only | Delivery of push messages to iPhones | EU; transfer to the US possible (DPF, SCCs) | Apple Developer Program License Agreement, data-processing terms | ☐ before MARA push goes live |

Push messages carry no personal data (`{"type": "sync"}`), only the device's push token is personal data. The privacy notice and the sub-processor list name both (MARA Stage 10, drafts).

Choosing the mail provider: EU company, servers in the EU, data-processing agreement available, SPF/DKIM support, a reasonable price for transactional mail. Then fill in the provider in `/unterauftragsverarbeiter` and `docs/ops/production.md#dns`.

Not sub-processors (no personal data): the external heartbeat service for the dead man's switch and the push service for alerts (Pushover); they receive only alert and host names. GlitchTip, Grafana, Loki and Uptime Kuma run on our own servers.

## Documents

| Document | Where | Status |
|---|---|---|
| Impressum | `/impressum` | Draft with placeholders |
| Datenschutzhinweise | `/datenschutz` | Draft; lawyer's review in Stage 11 |
| Nutzungsbedingungen (Beta) | `/nutzungsbedingungen`, version `beta-2026-12` | Draft; lawyer's review in Stage 11 |
| Vertrag zur Auftragsverarbeitung incl. TOM | `/auftragsverarbeitung` | Draft; TOM match the implementation as of Stage 7 |
| Unterauftragsverarbeiter | `/unterauftragsverarbeiter` | Mail provider missing |
| Verfahrensdokumentation (first draft) | [verfahrensdokumentation.md](verfahrensdokumentation.md) | Draft; review with a tax advisor in Stage 11 |
| Brief for the lawyer's review (incl. GoBD wording) | [lawyer-review.md](lawyer-review.md) | Ready to send (Stage 11) |
| Tax-advisor review and DATEV import | [tax-advisor-review.md](tax-advisor-review.md) | Ready (Stage 11) |
| Record of processing activities (Art. 30 GDPR) | [verarbeitungsverzeichnis.md](verarbeitungsverzeichnis.md) | Draft (Stage 9); lawyer's review in Stage 11 |
| Data-subject requests and deletion of closed accounts | [docs/ops/gdpr.md](../ops/gdpr.md) | Runbook (Stage 9) |
| MARA: works-council notice (shown to the owner before the trial or booking, M-BR-09) | `/mara/betriebsrat`; `…/maraBetriebsrat.ts` | Draft (MARA Stage 10) |
| MARA: information sheet for employees (Art. 13 GDPR, employer fills in its details) | `/mara/mitarbeiterinformation`; `…/maraMitarbeiterinformation.ts` | Draft with placeholders (MARA Stage 10) |
| MARA additions to the AVV, privacy notice, sub-processors and terms (section 10a) | as above | Drafts (MARA Stage 10); brief in [lawyer-review.md](lawyer-review.md#mara-add-on-stage-10) |

Changing the terms or the data-processing agreement: raise `ERIKA_TERMS_VERSION`, update the date in the content module, and inform users four weeks ahead (terms § 11).

## Open questions (from the requirements and design)
1. **GoBD wording** (requirements, open question 1): what may the terms say about the archive without a certification? The drafts describe what the system does ("verhindert technisch Änderungen und Löschungen bis zum Ende der Aufbewahrungsfrist") and promise no "GoBD compliance".
2. **Object Lock at contract end** (ADR 0013 follow-up): what happens to compliance-locked objects when the Hetzner contract ends? Read Hetzner's FAQ and reflect it in the Verfahrensdokumentation (chapter 5.4).
3. **Retention date without an issue date** (build plan, gap 7): the receipt date is used. Confirm with the tax advisor.
