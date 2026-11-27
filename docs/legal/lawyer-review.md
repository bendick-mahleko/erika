# Brief for the lawyer's review (NF-09, MVP acceptance item 5)

ERIKA is a SaaS for small businesses in Germany: it receives, checks, displays and archives e-invoices (XRechnung, ZUGFeRD), creates them, and exports them for tax advisors. Operator: [company, address]. Servers in Germany (Hetzner); payments through Stripe. The texts below are drafts by the development team and are marked `draft: true`.

## Documents to review

| Document | Where (rendered page; source) | Notes |
|---|---|---|
| Nutzungsbedingungen (Beta, later general terms) | `/nutzungsbedingungen`; `frontend/src/content/legal/nutzungsbedingungen.ts` | Turn the beta terms into terms for paying customers: price and payment by Stripe, 14-day trial, read-only after non-payment, termination and closure |
| Vertrag zur Auftragsverarbeitung (AVV) incl. TOM | `/auftragsverarbeitung`; `…/auftragsverarbeitung.ts` | Art. 28 GDPR. The TOM must match docs/security/asvs-l1.md and ADR 0027–0029 |
| Datenschutzhinweise | `/datenschutz`; `…/datenschutz.ts` | Must add Stripe (payments), the breached-password check (only 5 characters of a hash leave) and invitations |
| Unterauftragsverarbeiter | `/unterauftragsverarbeiter`; `…/unterauftragsverarbeiter.ts` | Hetzner; mail provider (to choose); Stripe |
| Impressum | `/impressum` | Placeholders |
| Verzeichnis von Verarbeitungstätigkeiten | docs/legal/verarbeitungsverzeichnis.md | Art. 30 |
| Data-subject requests and deletion | docs/ops/gdpr.md | 30-day answer; anonymisation instead of deletion where audit entries refer to a user |

## Questions for the lawyer

1. **Archive wording (Requirements, open question 1):** which statements about GoBD may the terms and the website make without a certification (e.g. IDW PS 880)? Current wording: the system "technically prevents changes and deletion until the end of the retention period", with no promise of "GoBD compliance". Options:
   - (a) describe the technical measures only, as now;
   - (b) "unterstützt die Anforderungen der GoBD an die Unveränderbarkeit";
   - (c) wait for a certification.
2. **Read-only after non-payment:** customers can always read and export their archive, but not change anything, and their data is kept until the end of the retention period. Is this enough with regard to the customer's own retention duties (§ 147 AO)? Are the notice periods in the terms adequate?
3. **Closure and deletion:** data of a closed account is deleted after the latest retention date of its originals. Who is responsible for retention after closure, ERIKA as processor or the customer? Contract wording?
4. **Object Lock at contract end:** originals are locked against deletion (COMPLIANCE mode) until their retention date, even for us. What does the AVV say if the customer demands earlier deletion (Art. 17 vs. § 147 AO)?
5. **Stripe:** Stripe is a separate controller for payment data, or a processor? Wording in the privacy notice.
6. **ERIKA's own e-invoices** are sent to the customer's email address and to their ERIKA inbox address. Any objection?
7. **Tax advisors** get read access through invitations by the customer. Is a separate agreement with the advisor needed?
8. **Liability for validation results:** ERIKA shows whether an invoice passes the official rules (KoSIT). Limitation of liability for wrong results?

## Deliverables

- Reviewed texts. We set `draft: false` and raise `ERIKA_TERMS_VERSION`; existing users accept the new version (ADR 0030).
- A written answer to questions 1–8.
- Sign-off date for the go-live checklist (docs/ops/go-live.md).

## MARA add-on (Stage 10)

MARA (job reports with customer signatures, photos and working times, ADR 0036–0043) adds texts and questions. Exit criterion of MARA Stage 10: this review has **started**.

| Document | Where | What changed |
|---|---|---|
| AVV | `/auftragsverarbeitung` § 2 | MARA data categories (jobs, reports, photos, signatures, working times, devices), data subjects (employees, people on photos), employee-data rules |
| Datenschutzhinweise | `/datenschutz` §§ 2, 6, 7 | MARA as processing on behalf; push tokens to Google/Apple (third-country transfer); crash reports and counters (Stage 9) |
| Unterauftragsverarbeiter | `/unterauftragsverarbeiter` | Google (FCM), Apple (APNs) |
| Nutzungsbedingungen § 10a | `/nutzungsbedingungen` | Add-on: trial, price with 3 users and seats, prorating, cancellation, read-only afterwards, signed reports |
| Hinweis zum Betriebsrat | `/mara/betriebsrat` | New (M-BR-09); the owner confirms it before the trial or booking (stored with date and person) |
| Informationsblatt für Beschäftigte | `/mara/mitarbeiterinformation` | New; the employer completes the placeholders and hands it out |

Questions:

9. **Signature approach:** the customer signs on the technician's phone; the app shows exactly the report that is signed; the server checks the SHA-256 of the canonical report and stores signature image, strokes, name and time; signed versions are locked by database triggers and archived write-once; a public page lets anyone with the code check the hash (ADR 0040, `docs/mara/BUILD_PLAN.md` Stage 7). Is this a sufficient "einfache elektronische Signatur" for an acceptance record (Abnahmeprotokoll, § 640 BGB) and as evidence? Wording for the terms ("gilt als Abnahme" is avoided so far)?
10. **Works council:** is the notice adequate, and is the owner's confirmation before the trial a sensible safeguard? Should ERIKA offer a template for a Betriebsvereinbarung?
11. **Employee information sheet:** does it meet Art. 13 GDPR as a template the employer completes? Retention of time data (default 2 years) — appropriate (working-time records, § 16 ArbZG; payroll)?
12. **Times visible to colleagues:** technicians on the same job see each other's times on the shared report (the customer sees them too when signing). Acceptable under § 26 BDSG, or should colleagues' times be hidden in the app (which would change how the signed report is built)?
13. **Push via Google/Apple:** the push token is the only personal data transferred; is DPF plus SCCs enough, and is the wording in the privacy notice right?
14. **Photos with people:** the app tells technicians not to photograph people or documents. If they do anyway, who is responsible (customer as controller), and is the AVV wording enough?

