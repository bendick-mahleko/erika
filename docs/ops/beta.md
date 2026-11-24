# Closed beta: onboarding, feedback, gate

Background: [ADR 0030](../adr/0030-closed-beta.md). Target: **20 beta users** (build plan Stage 7), from 15 Jan 2027 or the re-planned date.

## Invite someone

1. Agree on participation personally (phone or email). Explain:
   - it's a free beta;
   - real invoices are welcome, and they are archived write-once;
   - keep your usual filing during the beta (terms § 5).
2. On the app server:
   ```sh
   cd /opt/erika && ./invite.py anna@betrieb.de            # valid 21 days; --days 7 for shorter
   ```
   The link only works for this address. Send it from your own mailbox, together with the welcome text below. It isn't logged anywhere.
3. Note the person in the beta list (private spreadsheet, not in the repository): name, business, date invited, date signed up, how they receive invoices.

**Welcome text (template):**

> Hallo …,
>
> danke, dass Sie ERIKA testen! Mit diesem persönlichen Link legen Sie Ihr Konto an (gültig bis …): <Link>
>
> So geht's: Konto anlegen → E-Mail bestätigen → Authenticator-App einrichten (z. B. Google Authenticator, Microsoft Authenticator oder 2FAS) → Rechnungen hochladen oder Ihre Eingangsadresse an Lieferanten geben.
>
> Wenn etwas hakt oder fehlt: oben rechts auf „Feedback“ klicken oder an beta@erika-app.de schreiben. Wir melden uns innerhalb eines Werktags.

## Feedback

Channels:
- the **in-app feedback button**, which stores the message in `feedback` and mails beta@…;
- **email** to beta@…;
- an optional **15-minute call** after the first week of each user.

Triage weekly (and as part of the monthly release checklist):
```sql
-- as erika_owner (all workspaces)
SELECT f.created_at, f.category, f.page, f.message, w.name AS workspace
  FROM feedback f JOIN workspace w ON w.id = f.workspace_id
 WHERE f.created_at > now() - interval '7 days' ORDER BY f.created_at;
```
- Each piece of feedback gets an answer within one working day, at least "received".
- Bugs become GitHub issues (label `beta`). **Copy no invoice content into issues**; describe the case or ask for an anonymised file.
- Ideas go into the backlog with a vote count. In Stage 11 the blockers are fixed and the rest moves to the 1.1 backlog (build plan).

## Support access
The team has no "log in as customer" function. If a beta user needs help with a specific document, ask for a screenshot, or ask them to forward the file to a team workspace. Database access to customer data happens only for incident analysis, and is noted in the incident record.

## Beta gate

Exit criteria of Stage 7. All must be ticked before the first invitation goes out.

| Criterion | How it's checked | Done |
|---|---|---|
| IN-01–05, VW-01–06, AR-01–04 pass in production | Run the acceptance steps of each requirement with the team's test workspace in production (e2e flows: upload, email to intake address, ZIP, duplicate, viewer, PDF copy, attachments, status history, search) | ☐ |
| Restore from backup succeeded and was timed (target 4 h) | First drill on staging, then the first production drill ([restore.md](restore.md#drill-record)) | ☐ |
| Alerts fire in a test: site down, backup failed, audit chain broken | promtool tests in CI (done), plus the end-to-end test ([monitoring.md](monitoring.md#testing-the-alerts)) with time to notification | ☐ |
| Data-processing agreements signed with Hetzner and the mail provider; sub-processor list published | [docs/legal/README.md](../legal/README.md); `/unterauftragsverarbeiter` filled in | ☐ |
| Privacy notice, beta terms, data-processing agreement: placeholders filled in, reviewed | `/datenschutz`, `/nutzungsbedingungen`, `/auftragsverarbeitung`, `/impressum` | ☐ |
| Verfahrensdokumentation, first draft | [docs/legal/verfahrensdokumentation.md](../legal/verfahrensdokumentation.md) | ☐ |
| Status page public, uptime monitors active | https://status.erika-app.de | ☐ |
| Invoice creator in production: create → issue → send with the team's test workspace; email and file archived together; a test bounce marks the invoice "Zustellung fehlgeschlagen" | Stage 8 exit criteria (ADR 0031); timed manual test from saved customer and article < 3 minutes (CR-05) | ☐ |
| Stage 9: an advisor invited from the test workspace reads, comments and exports; the DATEV file of a period export is imported by a tax advisor (S9-a, EX-01); a full export of the test workspace matches the archive | ADR 0032; record the advisor's feedback on accounts and tax keys | ☐ |
| Billing stays off during the beta (`GET /api/v1/billing` → `enabled: false`); before go-live: pricing decided, Stripe set up and the test-clock run done ([billing.md](billing.md)) | Stage 10 exit criteria (ADR 0033) | ☐ |
| Invite-only sign-up active in production | `GET /api/v1/auth/signup-policy` → `inviteOnly: true` | ☐ |
| **Closed beta starts with 20 users** | Beta list | ☐ |
