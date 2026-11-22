# 0005. Neutral ParsedInvoice model stored as JSONB

- Status: Accepted
- Date: 2026-11-21 (Software Design v1.1, ADR 005)

## Decision
`invoice-core` maps UBL, CII and ZUGFeRD into one neutral model; `parsed_invoice` stores it as JSONB plus indexed search columns.

## Consequences
New business terms need no migration. Search columns are extracted explicitly.
