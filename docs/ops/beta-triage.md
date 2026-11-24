# Beta feedback triage (Stage 11)

Beta feedback arrives in the app (the feedback button stores it in the `feedback` table and emails `ERIKA_FEEDBACK_TO`), from support emails, and from the usability and accessibility sessions.

## Collect

```sql
SELECT f.created_at, f.category, f.page, left(f.message, 200) AS message, w.name AS workspace
  FROM erika.feedback f JOIN erika.workspace w ON w.id = f.workspace_id
 WHERE f.created_at > :since ORDER BY f.created_at;
```

Run it as the schema owner (support access, [beta.md](beta.md#support-access)). Copy each item into the tracker, without invoice content.

## Classify

| Class | Meaning | Before go-live? |
|---|---|---|
| Blocker | Data loss or risk, legal problem, a Must requirement not met, a main flow impossible | Fix |
| Major | Main flow only with help or a workaround; frequent confusion | Fix if small, else 1.1 with a workaround documented |
| Minor | Friction, wording, nice to have | 1.1 backlog |
| Idea | New feature | Product backlog (Should/Could items, release plan) |

Cross-check against the release plan: items already planned for 1.1 (allow-list, reminders, GiroCode, logo, recurring invoices, English UI) go there unchanged.

## Record

| # | Date | Source | Summary | Class | Decision | Done in |
|---|---|---|---|---|---|---|
| 1 | 23 Nov 2026 | Stage 11 review | No way to reset a forgotten password | Blocker | Fixed: reset by email, change in settings | Stage 11 |
| 2 | 24 Nov 2026 | ZAP baseline | Forms sent credentials in the URL without JavaScript | Blocker | Fixed: all forms POST | Stage 11 |
| | | | | | | |

Go-live needs no open blocker (go-live checklist).
