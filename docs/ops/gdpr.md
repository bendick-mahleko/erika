# Data-subject requests and account deletion (NF-09)

ERIKA is a processor for its customers' data (invoices, their business partners) and a controller for the account data of its users (name, email, sign-in data). This runbook covers both. Legal texts: `/datenschutz`, `/auftragsverarbeitung`; record of processing activities: [docs/legal/verarbeitungsverzeichnis.md](../legal/verarbeitungsverzeichnis.md).

## Who asks, and what to do

| Request comes from | About | What we do |
|---|---|---|
| A user, about their own account (name, email, sign-ins) | Art. 15–21 GDPR, ERIKA is controller | Answer within one month (Art. 12(3)); steps below |
| A business partner of a customer (e.g. a supplier named on an invoice) | Data in a customer's workspace; ERIKA is processor | Don't answer the content. Forward the request to the workspace owner within 3 working days (data-processing agreement § 6) and tell the requester that it was forwarded |
| A workspace owner, about their workspace | Their own processing | They use the app: full account export (`/exports`), account closure (`/settings/workspace`) |

Always check the requester's identity first: the request must come from the account's email address, or be confirmed from it. Never send data to another address.

Log every request in the support tracker: date, requester, type, steps, date answered. Retain the log for 3 years.

## Steps for a user's own data

1. **Access (Art. 15).** Export as the schema owner (support access, [beta.md](beta.md#support-access)), read-only:
   ```sql
   SELECT id, email, display_name, locale, created_at, email_verified_at, totp_enabled_at, terms_version, terms_accepted_at
     FROM erika.app_user WHERE lower(email) = lower(:email);
   SELECT m.workspace_id, w.name, m.role, m.created_at FROM erika.membership m JOIN erika.workspace w ON w.id = m.workspace_id
    WHERE m.user_id = :user_id;
   SELECT workspace_id, seq, at, action FROM erika.audit_log WHERE actor = :user_id ORDER BY at;
   ```
   Send the result as a file to the account address. Mention the purposes and recipients from the record of processing activities.
2. **Rectification (Art. 16).** Users change name and language in the app; email changes by support (update `app_user.email`, then send a verification).
3. **Erasure (Art. 17).**
   - Remove the user from every workspace (the owner does it in `/settings/team`, or support with the owner's consent). A sole owner first closes the workspace or makes someone else owner.
   - Then anonymise the account; don't delete it, because audit entries refer to the ID (GoBD):
     ```sql
     UPDATE erika.app_user
        SET email = 'geloescht-' || id || '@invalid', display_name = 'Gelöschter Benutzer', password_hash = '!',
            totp_secret = NULL, totp_enabled_at = NULL, locked_until = 'infinity'
      WHERE id = :user_id;
     DELETE FROM erika.recovery_code WHERE user_id = :user_id;
     DELETE FROM erika.known_device WHERE user_id = :user_id;
     DELETE FROM erika.spring_session WHERE principal_name = :user_id::text;
     ```
   - Comments and history entries keep the user ID; they show "ehemaliges Mitglied".
4. **Portability (Art. 20).** Data a user put into a workspace belongs to the workspace: the owner exports it (full account export).

## Closed accounts: deletion after the retention period

A closed workspace (`workspace.closed_at`) is kept until `workspace.delete_after`, the last retention date of its originals (ADR 0032). Every month:

1. List workspaces due for deletion:
   ```sql
   SELECT id, name, closed_at, delete_after FROM erika.workspace WHERE closed_at IS NOT NULL AND delete_after < current_date;
   ```
2. Check legal holds: `SELECT count(*) FROM erika.stored_object WHERE workspace_id = :id AND legal_hold`. With a hold, don't delete; ask the customer.
3. Delete. The automatic deletion job comes with Stage 11. Until then, two administrators do it together and record it:
   - delete the database rows of the workspace (all tenant tables) as the schema owner;
   - let S3 lifecycle remove the expired objects (their Object Lock has run out by then).
4. Note the deletion in the support tracker.

## Breaches

A personal-data breach is handled by the incident runbook. Customers must be told without undue delay (data-processing agreement § 7), and the supervisory authority within 72 hours where ERIKA is controller (Art. 33).
