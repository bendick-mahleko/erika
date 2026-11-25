# 0039. The TECHNICIAN role

- Status: Accepted (web side implemented in MARA Stage 2; the app sign-in follows in Stage 3)
- Date: 2026-11-25

## Context
ERIKA has three roles: OWNER, MEMBER and ADVISOR (read-only, ADR 0032). MARA adds technicians, who work only on their own or assigned work orders, on a phone, and usually must not see prices (M-AC-01). The MARA design's OFFICE role is what ERIKA calls MEMBER.

## Decision
- **New role TECHNICIAN** in `membership.role`. Only owners invite technicians.
- **Staff level and price visibility** belong to MARA, not to identity: the `workorders` module keeps them per person in `staff_profile` (staff level from `staff_level`, flag `can_see_prices`, default off), keyed by user. Owners change them (`PUT /staff/{memberId}`). This keeps identity free of MARA's pricing data and respects the module boundaries.
- **Who can be assigned** comes from identity's published `WorkspaceContacts.workforce()`: owners, members and technicians, never tax advisors.
- **App only.** A TECHNICIAN can't open a web session: the web sign-in answers `TECHNICIAN_USE_APP`. Their requests come only through `/api/v1/mobile/**` with a device token (ADR 0037). This keeps the web contract's harnesses and the rule "every state-changing web request needs OWNER or MEMBER" unchanged. Someone who does office work and fieldwork gets MEMBER, and MEMBER may also use the app.
- **Visibility.**
  - Technicians see work orders they are assigned to or created, with their customers and sites, through the sync scope (ADR 0038).
  - They see prices and rates only with `can_see_prices`.
  - They see their own time entries, never other people's (M-NF-09).
  - Owners and members see everything in the workspace.
- **Tax advisors** stay read-only, and `AdvisorReadOnlyIT` is unchanged. Advisors don't use the app.
- **Seats.** Billing counts active OWNER, MEMBER and TECHNICIAN memberships for the add-on (Stage 10).

## Consequences
- The role check for mobile endpoints is in the sync scope rules and `@PreAuthorize` on the mobile controllers. The mobile harness tests that a technician can't reach unassigned work orders.
- There is no web view for technicians in the MVP. If firms ask for one, it needs its own ADR and a harness extension.
