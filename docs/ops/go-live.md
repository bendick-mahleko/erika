# Go-live: MVP checklist and launch steps

The MVP goes live when every line of section 1 is ticked. [docs/testing/traceability.md](../testing/traceability.md) shows the current status of each requirement.

## 1. Acceptance (Requirements §8)

| Item | Evidence | Done |
|---|---|---|
| All 26 Must requirements pass | traceability.md all green, including CR-05 (usability test), EX-01 (tax advisor's DATEV import) and EX-03 (Stripe test clock) | ☐ |
| 100 % of the official test files parsed, results match KoSIT | CI `backend` (`ConformanceIT`) green on the release commit | ☐ |
| Every generated invoice type validates | CI `backend` (`GeneratedInvoicesConformanceIT`) green | ☐ |
| Restore test from backup succeeded | Second drill in production-like conditions, timed ([restore.md](restore.md#drill-record)) | ☐ |
| Lawyer reviewed terms, AVV, privacy policy | [lawyer-review.md](../legal/lawyer-review.md) answered; texts `draft: false` | ☐ |
| ASVS L1 complete, no open high finding | [asvs-l1.md](../security/asvs-l1.md); F-06 (object storage encryption) closed | ☐ |

## 2. Further gates (build plan, Stage 11)

| Item | Evidence | Done |
|---|---|---|
| Load test passed | [load-test-results.md](load-test-results.md) on staging | ☐ |
| Accessibility: manual check without open blockers | [accessibility-manual.md](../testing/accessibility-manual.md) | ☐ |
| Usability: ≥ 4 of 5 participants succeed | [usability-nf12.md](../testing/usability-nf12.md) | ☐ |
| Verfahrensdokumentation reviewed by a tax advisor | [tax-advisor-review.md](../legal/tax-advisor-review.md) | ☐ |
| Beta feedback triaged; blockers fixed | [beta-triage.md](beta-triage.md) | ☐ |
| Data-processing agreements signed: Hetzner, mail provider, Stripe | [docs/legal/README.md](../legal/README.md) | ☐ |
| Pricing decided; Stripe live mode set up | [billing.md](billing.md) §§ 1–3 | ☐ |
| ZAP baseline and Trivy green on the release commit | CI `e2e` | ☐ |

## 3. Launch steps (release day)

1. Tag the release (`vX.Y.Z`), run the [release checklist](release-checklist.md), deploy.
2. Switch to public sign-up and turn billing on, in group_vars or `.env` of production:
   - `erika_signup_mode: OPEN` (was `INVITE_ONLY`);
   - `erika_billing_enabled: true`, the Stripe price and tax-rate IDs, `erika_billing_issuer_workspace`;
   - `erika_terms_version`: the reviewed version.

   Then apply it: `ansible-playbook site.yml --limit app`.
3. Set the trial end of the beta users ([billing.md §4](billing.md)).
4. Optional: price text on the landing page, `ERIKA_PRICE_TEXT` on the `web` service (e.g. "12 € netto im Monat, 14 Tage kostenlos testen").
5. Check, logged out:
   - `/` shows "14 Tage kostenlos testen";
   - `GET /api/v1/auth/signup-policy` → `inviteOnly: false`;
   - the status page is public and green.

   Then sign up a test account end to end.
6. Announce: beta users by email (new terms, trial end), website, status page notice.
7. Watch for 48 hours:
   - Grafana: errors, job queue;
   - GlitchTip;
   - Stripe dashboard: webhook deliveries.

## Rollback

- Sign-up: set `erika_signup_mode: INVITE_ONLY` and redeploy. Existing accounts are unaffected.
- Billing: `erika_billing_enabled: false` lifts every limit at once. Stripe subscriptions keep running; pause them in Stripe if needed.
- Code: deploy the previous image tag ([production.md](production.md)). Migrations are expand-only, so the old version runs against the new schema.
