-- Load test data (NF-07, ADR 0034): 10,000 received invoices in one workspace, like a busy
-- customer after a few years. Run as the schema owner against a TEST database only:
--   psql -v ws=<workspace-uuid> -v n=10000 -f loadtest/seed.sql
-- Originals are placeholders (size 1, no object in storage): the load test reads lists, search
-- and the invoice view, which come from the database. The view JSON is copied from one real,
-- processed document of the same workspace (upload one first; prepare.mjs does).
\set ON_ERROR_STOP on
SET search_path = erika;

INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, retention_until, object_lock)
SELECT :'ws', md5(:'ws' || 'lt' || i) || md5('lt' || i || :'ws'), 1, 'application/xml', 'loadtest/' || i, '2034-12-31', 'NONE'
  FROM generate_series(1, :n) i
ON CONFLICT DO NOTHING;

INSERT INTO document (id, workspace_id, direction, doc_type, format, format_version, sha256, file_name, media_type, size_bytes,
                      status, processing, received_via, created_at)
SELECT md5('ltdoc' || i || :'ws')::uuid, :'ws', 'IN', 'INVOICE', 'XRECHNUNG_UBL', 'XRECHNUNG',
       md5(:'ws' || 'lt' || i) || md5('lt' || i || :'ws'), 'rechnung-' || i || '.xml', 'application/xml', 1,
       (ARRAY['NEW','CHECKED','APPROVED','PAID','BOOKED'])[1 + i % 5], 'DONE', 'UPLOAD', now() - make_interval(mins => i * 7)
  FROM generate_series(1, :n) i
ON CONFLICT DO NOTHING;

WITH template AS (
    SELECT p.data, p.view FROM parsed_invoice p
     WHERE p.workspace_id = :'ws' AND p.view IS NOT NULL
     ORDER BY p.parsed_at LIMIT 1
)
INSERT INTO parsed_invoice (document_id, workspace_id, invoice_no, issue_date, due_date, currency, seller_name, seller_tax_id,
                            buyer_name, net, tax, gross, data, view, search_text)
SELECT md5('ltdoc' || i || :'ws')::uuid, :'ws', 'LT-' || i, current_date - (i % 1500), current_date - (i % 1500) + 14, 'EUR',
       'Lieferant ' || (i % 400) || ' GmbH', 'DE' || lpad((i % 400)::text, 9, '0'), 'Lasttest GmbH',
       (i % 2000) + 100, ((i % 2000) + 100) * 0.19, ((i % 2000) + 100) * 1.19,
       (SELECT data FROM template), (SELECT view FROM template),
       'LT-' || i || ' Lieferant ' || (i % 400) || CASE WHEN i % 40 = 0 THEN ' Fassadenfarbe' ELSE ' Bürobedarf' END
  FROM generate_series(1, :n) i
ON CONFLICT DO NOTHING;

ANALYZE document;
ANALYZE parsed_invoice;
SELECT count(*) AS documents FROM document WHERE workspace_id = :'ws';
