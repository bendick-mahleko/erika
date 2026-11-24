// NF-07 / AR-04 load test: 50 concurrent users work in a workspace with 10,000 documents:
// inbox list, search, filters, opening an invoice (details and view).
//
//   k6 run -e LT_BASE_URL=http://localhost:3000 loadtest/browse.js
// Needs loadtest/.session.json from prepare.mjs and the seed (loadtest/README.md).
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const base = __ENV.LT_BASE_URL || "http://localhost:3000";
const s = JSON.parse(open("./.session.json"));
const params = {
  headers: { Cookie: `ERIKA_SESSION=${s.session}`, "X-Forwarded-For": s.ip },
  tags: {},
};

const listTime = new Trend("erika_list_ms", true);
const searchTime = new Trend("erika_search_ms", true);
const viewTime = new Trend("erika_view_ms", true);

export const options = {
  scenarios: {
    users: { executor: "constant-vus", vus: Number(__ENV.LT_VUS || 50), duration: __ENV.LT_DURATION || "5m" },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    // AR-04: search over 10,000 invoices < 1 s; NF-07: viewer < 2 s (95th percentile).
    erika_search_ms: ["p(95)<1000"],
    erika_list_ms: ["p(95)<1000"],
    erika_view_ms: ["p(95)<2000"],
  },
};

const words = ["Lieferant 17", "Fassadenfarbe", "LT-42", "Bürobedarf", "Lieferant 3 GmbH", "GmbH"];

export default function () {
  const list = http.get(`${base}/api/v1/documents?limit=50`, params);
  listTime.add(list.timings.duration);
  check(list, { "list 200": (r) => r.status === 200 });
  const items = list.status === 200 ? list.json("items") : [];
  sleep(1 + Math.random() * 2);

  const q = words[Math.floor(Math.random() * words.length)];
  const search = http.get(`${base}/api/v1/documents?limit=50&q=${encodeURIComponent(q)}`, params);
  searchTime.add(search.timings.duration);
  check(search, { "search 200": (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);

  const filtered = http.get(`${base}/api/v1/documents?limit=50&status=NEW&from=2025-01-01&to=2026-12-31`, params);
  listTime.add(filtered.timings.duration);
  check(filtered, { "filter 200": (r) => r.status === 200 });

  if (items.length > 0) {
    const doc = items[Math.floor(Math.random() * items.length)];
    const started = Date.now();
    const detail = http.get(`${base}/api/v1/documents/${doc.id}`, params);
    const view = http.get(`${base}/api/v1/documents/${doc.id}/view`, params);
    viewTime.add(Date.now() - started);
    check(detail, { "detail 200": (r) => r.status === 200 });
    check(view, { "view 200": (r) => r.status === 200 });
  }
  sleep(2 + Math.random() * 3);
}
