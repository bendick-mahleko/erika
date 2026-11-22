# 0001. Own EN 16931 syntax mapping instead of Mustangproject's model for parsing

- Status: Accepted
- Date: 2026-11-22

## Context
The design proposes Mustangproject for reading and writing. VW-01 requires that *every* EN 16931 business term present in a file is shown. Mustangproject's object model is built for generating ZUGFeRD and doesn't carry all business terms (e.g. tax representative, invoiced object identifier, several line-level references), and reading UBL goes through a conversion step.

## Decision
`invoice-core` maps both syntaxes itself, directly from the EN 16931-3-2 (UBL) and EN 16931-3-3 (CII) syntax bindings, onto its own neutral model (`de.erika.invoicecore.model`):
- hardened DOM parsing (`SafeXml`: DTDs rejected, no external access);
- a small element-path navigator (`El`) instead of XPath: simpler, faster, and easy to compare with the binding tables;
- PDFBox only to extract the embedded XML from PDF/A-3 files.

Mustangproject stays the candidate for **writing** (Stage 8); see ADR 0004.

## Consequences
- Full business-term coverage, enforced by `ModelCompletenessTest`.
- Mapping mistakes are caught by the cross-syntax equivalence test over the XRechnung UBL/CII pairs, which doesn't depend on the parser's own output.
- We maintain the mapping when the standards change (EN 16931 is stable; XRechnung 4.0 will need a review).
- Normalisations: BT-8 from UNTDID 2475 (CII) to 2005; UBL `OrderReference/ID = "NA"` placeholder dropped; BT-21 from the UBL `#CODE#` note prefix.
- XRechnung extensions supported so far: sub invoice lines (UBL `SubInvoiceLine`, CII `ParentLineID`), third-party payments BG-DEX-09 (UBL).
