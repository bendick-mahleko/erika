# Accessibility check of the main flows (NF-11, WCAG 2.1 AA)

What is already automated:
- **axe** (WCAG 2.0/2.1 A and AA rules) runs in every Playwright test, on every main page, at desktop and at 375 px width;
- **keyboard only:** `e2e/hardening.spec.ts` uses the skip link, opens a document from the inbox and writes a comment without the mouse.

axe finds about a third of WCAG problems. The rest needs a person.

## Setup

- NVDA (current version) with Firefox on Windows, and VoiceOver with Safari on macOS or iOS.
- Keyboard only; zoom 200 % and 400 % (reflow at 320 CSS px); Windows high-contrast mode.
- Test workspace on staging, prepared as for the [usability test](usability-nf12.md).

## Flows and checks

| Flow | Check |
|---|---|
| Sign-up, email link, 2FA set-up, sign-in | Labels read out; errors announced (live region); QR code has a text alternative (secret shown as text); focus moves sensibly after each step |
| Inbox: upload, filters, list, bulk status | Upload button reachable and announced; results announced; table or list structure clear; status change confirmed |
| Viewer | Headings structure the sections (h1 → h2); every field has its German label; validation result announced with its level; downloads say what they are |
| Invoice creator | Form fields grouped (fieldset/legend); missing fields announced and linked; autosave status announced (`aria-live`) |
| Team, export, billing settings | Tables with header cells; buttons named with their target ("Entfernen Max Mustermann") |
| Global | Skip link first; visible focus everywhere; no keyboard trap; page titles unique; language `de`; contrast ≥ 4.5:1 (text) and 3:1 (UI) |

For each problem record: page, WCAG criterion, severity (blocker / major / minor), screenshot or screen-reader transcript.

## Browsers (NF-14)

Current Chrome and Edge are covered by Playwright (Chromium). Firefox and Safari (macOS and iOS) are checked by hand:
- sign-in;
- upload;
- the viewer on a phone (375 px);
- downloading the original.

## Record

| Date | Tester | Assistive technology / browser | Flow | Findings | Fixed in |
|---|---|---|---|---|---|
| | | | | | |

Go-live needs no open blocker or major finding.
