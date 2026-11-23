# 0025. Viewer, PDF view copy and derived objects

- Status: Accepted
- Date: 2026-11-23

## Context
Stage 6 has to make an XRechnung readable without help:
- VW-01: every business term present is shown with a German label;
- VW-02: a PDF view copy, marked as a copy on every page (BR-01: the original is the e-invoice file);
- VW-06: embedded attachments download in their original format, never inline;
- NF-07: the viewer data of a 500-line invoice arrives in under 2 s.

Software Design §7 keeps PDF processing (PDFBox) in the worker. Reading a ZUGFeRD hybrid means opening the PDF, and so does extracting its attachments.

## Decision
**invoice-core 0.3.0** gets the presentation, so the trades and Nebenkosten apps can reuse it:
- `BtLabels`: German labels for BT-1…165, BG-1…32 and the XRechnung third-party-payment extension.
- `InvoiceView.of(invoice)`: sections grouped as on a paper invoice (head, references, parties with addresses and contacts, delivery, payment, allowances and charges, totals, VAT breakdown, supporting documents, lines with sub-lines). Each field carries its BT ID, label and a value formatted for German readers:
  - dates as `02.11.2026`;
  - amounts as `1.785,00 EUR`, keeping any decimals written in the file;
  - common codes with their meaning (`58 – SEPA-Überweisung`, `HUR – Stunde`).
- `ViewRenderer.pdf(view)`: an A4 PDF with the PDF standard fonts. "Ansichtskopie – Original ist die XML-Datei" is printed at the top and in the footer of every page. Characters the standard fonts can't show become `?`, and `printable()` says exactly what is on the page.

**The worker builds everything once**, in `documents.parse-and-validate`, from the same parse that validates:
- the view, as JSON in `parsed_invoice.view`;
- the PDF view copy and each decoded attachment, as **derived objects** in the bucket `erika-derived` (`ws/<workspace>/<document>/<kind>/<index>`), with a `derived_object` row holding the SHA-256, media type and file name.

**The API only reads**, so it never parses or renders a file:
- `GET /documents/{id}/view` returns the stored JSON;
- `GET /documents/{id}/view-pdf` and `/attachments/{i}` return the derived objects after checking their SHA-256.

Derived objects can always be rebuilt from the original: `POST /documents/{id}/revalidate`. Their bucket has no Object Lock and may be emptied.

**Downloads** are always `Content-Disposition: attachment`, with `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox` and `Cache-Control: private, no-store`. The original (`GET /documents/{id}/original`) comes from the archive with the hash checked on open (ADR 0022) and its digest in `Repr-Digest` (RFC 9530) and `Digest` (RFC 3230).

**Existing documents.** Migration V5 queues a new read for every document processed before Stage 6, so they get a view too.

## Consequences
- Viewer requests are cheap (one JSON column); a 500-line invoice is well under the 2 s budget (`DocumentsIT`).
- The view is stored in German. English labels (VW-07, later) mean an `en` label table and a revalidation run, or building the view on request from `parsed_invoice.data`.
- The infrastructure needs a third bucket (`ERIKA_S3_DERIVED_BUCKET`), with read/write/list for the app key.
- `InvoiceViewTest` (invoice-core) checks every official test file: each business term present appears with its label (VW-01); the PDF carries the marking on every page and contains every value (VW-02).
