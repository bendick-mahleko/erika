# 0030. Closed beta: invitations, terms, feedback, legal pages

- Status: Accepted
- Date: 2026-11-23

## Context
Stage 7 ends with a closed beta of 20 users. The build plan asks for:
- invite-only sign-up, a feedback channel and an in-app feedback button;
- a draft privacy policy and beta terms;
- a published sub-processor list (NF-01);
- a data-processing agreement at sign-up (Software Design §7: "ERIKA is a processor for its customers (data-processing agreement at sign-up)").

Invitations of members into an existing workspace come in Stage 9, so they are not part of this.

## Decision
**Invite-only sign-up** (`erika.signup.mode=INVITE_ONLY` on staging and production; `OPEN` locally and from go-live).
- An invitation is a signed link: `v1.<base64url(email|expiry)>.<base64url(HMAC-SHA256)>`, using `ERIKA_INVITE_SECRET`.
- It is bound to one email address. Because a second sign-up with a registered address only sends the "already registered" notice, a link can't create a second account. That makes it single-use without a table.
- The operator creates links with `infra/production/invite.py`. `BetaInvitationsTest` checks that the script and the back end produce the same link.
- `GET /auth/signup-policy` tells the front end to explain the closed beta instead of showing a form that would fail.
- Errors: `INVITATION_REQUIRED` and `INVITATION_INVALID` (403).

**Terms at sign-up.**
- `termsAccepted` is required in `POST /auth/signup` (else 400 `TERMS_NOT_ACCEPTED`). The checkbox covers the beta terms of use including the data-processing agreement, and confirms that the privacy notice has been read. Under the GDPR the privacy notice is information, not something to accept.
- The accepted version (`erika.signup.terms-version`, now `beta-2026-12`) and the time are stored on `app_user` and audited (`identity.terms.accepted`). New terms versions are re-accepted in a later stage; until then, changes follow the notice procedure in the terms (§ 11).

**Feedback.**
- `POST /feedback` (category and message, plus the page path without its query string) is stored in the tenant table `feedback` and mailed to `ERIKA_FEEDBACK_TO`, with the sender's name and address so the team can answer.
- Every role may send feedback, **tax advisors included**: it changes no workspace data. This is an explicit exception in the security configuration and `@PreAuthorize`, tested in `FeedbackIT`.
- The feedback button in the header opens an accessible native `<dialog>`.
- The beta's other channels (email address, short calls) are in docs/ops/beta.md.

**Legal pages** are public routes in German: `/impressum`, `/datenschutz`, `/nutzungsbedingungen`, `/auftragsverarbeitung` (with the technical and organisational measures as Annex 1) and `/unterauftragsverarbeiter`. They are linked in the footer.
- The texts are content modules (`frontend/src/content/legal/*.ts`, a small Markdown subset without HTML), not UI strings: they are German-only documents, and the German text is binding.
- Each carries `draft: true`, which shows a notice, until a lawyer has reviewed it (Stage 11, NF-09). Placeholders for the operator's details are in [square brackets].

## Consequences
- Rotating `ERIKA_INVITE_SECRET` invalidates all open invitations, which is intended. Accounts already created are unaffected.
- Going public in Stage 11 means `ERIKA_SIGNUP_MODE=OPEN`, with no code change.
- Signing the data-processing agreements with Hetzner and the mail provider, filling in the placeholders, and the lawyer's review are tasks for people (docs/legal/README.md). The pages can't replace them.
