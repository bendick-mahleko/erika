# Tax-advisor review: Verfahrensdokumentation and DATEV import

Two things need a tax advisor before go-live:
1. the final review of the Verfahrensdokumentation;
2. the import of an ERIKA DATEV file into DATEV (spike S9-a, EX-01).

## 1. Verfahrensdokumentation

Document: [verfahrensdokumentation.md](verfahrensdokumentation.md) (structure follows the GoBD and the AWV sample).

Questions:
- Is the description of receipt, checking, archiving and retention enough for an audit (Betriebsprüfung)?
- Retention date for documents without an issue date: the receipt date is used (build plan, gap 7). Agreed?
- Is the full account export (originals, data as JSON, audit log, checksums) suitable for data access by the tax office (Z3, Datenträgerüberlassung)? Is a different format needed (e.g. GDPdU description file `index.xml`)?
- Outgoing invoices: numbering, corrections only by credit note or cancellation, the sent email archived with the file. Anything missing?

## 2. DATEV import (EX-01)

1. In a test workspace with real-looking received and issued invoices, enter consultant number, client number and chart of accounts under *Einstellungen → DATEV*.
2. Export one month (*Export → Zeitraum*) and send the ZIP to the advisor.
3. The advisor imports `DATEV/EXTF_Buchungsstapel_*.csv` in *DATEV Rechnungswesen → Stapelverarbeitung → Import ASCII-Daten* (format "DATEV-Format", Buchungsstapel).
4. Checks:
   - the import runs without errors;
   - amounts and debit/credit are right;
   - the tax keys are correct (9/8 input VAT, 3/2 output VAT);
   - the accounts are usable (defaults: creditor 70000, debtor 10000, expense 4900/6300, revenue 8200/4200);
   - Belegfeld 1 holds the invoice number, Belegdatum is right;
   - reverse charge and intra-EU invoices carry no key, as intended (left to the advisor).
5. Record the feedback below. Adjust the defaults in `DatevCsv`/`DatevChart` if needed (unit test `DatevCsvTest`).

| Date | Advisor (firm) | DATEV product and version | Import result | Feedback | Changes made |
|---|---|---|---|---|---|
| | | | | | |

EX-01 is done when the import is accepted without errors.
