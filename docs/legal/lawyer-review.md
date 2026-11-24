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
