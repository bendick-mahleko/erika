# Usability test protocol (NF-12, CR-05)

**Goal:** a first-time user opens and understands an XRechnung without help. It passes if **4 of 5** participants succeed. The same sessions time CR-05: an invoice from a saved customer and article in under 3 minutes.

## Participants

Five people from the target group, not involved in ERIKA:
- owners or office staff of small businesses (trades, services);
- at least two who have never seen an XRechnung;
- at least one aged 55+;
- at least one who works mostly on a phone.

Recruit from beta waiting lists or partner businesses. Offer a voucher (e.g. €30). Collect consent with the form below.

## Setup

- Staging, or production with the team's test workspace, prepared as follows:
  - one valid XRechnung from the e2e fixture, received by email;
  - one XRechnung with an error (wrong total);
  - one plain PDF;
  - one saved customer and one saved article.
- One participant at a time, 30 minutes, moderator plus note-taker. Remote (screen sharing) or on site.
- Think-aloud. The moderator doesn't help. A hint counts as "with help".

## Tasks and success criteria

| # | Task (read out as written) | Success | Measure |
|---|---|---|---|
| 1 | "A supplier sent you an invoice by email. Find it in ERIKA and open it." | Viewer of the right invoice open without help | time, path |
| 2 | "Who sent the invoice, how much do you have to pay, and by when?" | Seller, gross amount and due date named correctly | correct/incorrect |
| 3 | "Is this invoice in order? If not, what is wrong?" (the faulty one) | Says it is faulty and names the reason in their own words (total doesn't add up) | correct/incorrect |
| 4 | "Write an invoice to customer X for 3 hours of article Y and send it." | Issued and sent; **time < 3 minutes** from opening "Rechnungen" (CR-05) | time |
| 5 | "Your tax advisor wants last month's invoices. Give them to her." | Export started, or advisor invited | yes/no |

**NF-12 counts tasks 1–3.** A participant succeeds if all three are done without help.

After the tasks:
- System Usability Scale (10 questions, German version);
- three open questions: what was unclear, what was missing, would you use it.

## Recording

One sheet per participant with:
- tasks 1–5: success (yes / with help / no), time, notes;
- the SUS score;
- quotes.

Summary: NF-12 result (x of 5), CR-05 times, average SUS, problems ranked by severity:
- blocker: task impossible;
- major: task needed help;
- minor: friction.

Blockers are fixed before go-live; the rest goes to the 1.1 backlog ([beta triage](../ops/beta-triage.md)).

## Consent (German, read out and signed)

> Ich nehme freiwillig an einem Test der Software ERIKA teil. Bildschirm und Ton werden zur Auswertung aufgezeichnet und nach spätestens 3 Monaten gelöscht. Meine Angaben werden nur anonymisiert ausgewertet. Ich kann jederzeit ohne Angabe von Gründen abbrechen.
> Ort, Datum, Unterschrift

## Results

| Date | Participant (anonymous) | T1 | T2 | T3 | NF-12 | T4 time | SUS | Notes |
|---|---|---|---|---|---|---|---|---|
| | P1 | | | | | | | |
| | P2 | | | | | | | |
| | P3 | | | | | | | |
| | P4 | | | | | | | |
| | P5 | | | | | | | |

Verdict NF-12: ☐ passed (≥ 4 of 5) · CR-05: ☐ every participant < 3 min
