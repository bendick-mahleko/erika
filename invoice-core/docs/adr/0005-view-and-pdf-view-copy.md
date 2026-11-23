# 0005. Invoice view and PDF view copy

- Status: Accepted
- Date: 2026-11-23

## Decision
- Version 0.3.0 adds the package `view` to the public surface:
  - `BtLabels`: German labels for every EN 16931 business term and group, plus the XRechnung third-party-payment extension;
  - `InvoiceView` / `ViewSection` / `ViewField` / `ViewAttachment`: the invoice grouped for reading, each value formatted for German readers and tagged with its BT ID;
  - `ViewRenderer.pdf`: an A4 PDF "view copy" with a marking on every page.
- The view is built by hand per section, not by reflection over `@Bt`. That keeps a deliberate order and grouping, and needs no `kotlin-reflect` at run time. Completeness is enforced by a test instead: for every official test file, each business term present in the parsed model must appear in the view, with its label (`InvoiceViewTest`).
- The renderer uses the PDF standard fonts (WinAnsi), so no font files are shipped. Characters outside WinAnsi become `?`; `ViewRenderer.printable` returns the exact text on the page. Labels that don't fit their column get a line of their own, so text extraction never interleaves label and value.
- Still only PDFBox at run time.

## Consequences
- An English view (ERIKA VW-07) needs a second label table and a locale parameter; that is a minor version.
- Embedding a Unicode font for rare scripts would remove the `?` replacement; it's not needed for the official test files.
