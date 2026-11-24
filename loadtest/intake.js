// NF-07 intake load: 1,000 emails per hour with an XRechnung attachment, delivered the way
// Postfix delivers them (POST /internal/intake/mail with the shared secret, api/internal.yaml).
// Run against the API directly (the internal endpoint is not routed by the reverse proxy):
//
//   k6 run -e LT_API_URL=http://localhost:8080 -e LT_INTAKE_SECRET=dev-intake-secret loadtest/intake.js
//
// Afterwards check that the worker kept up: loadtest/README.md ("Did the worker keep up?").
import http from "k6/http";
import { check } from "k6";
import encoding from "k6/encoding";

const api = __ENV.LT_API_URL || "http://localhost:8080";
const secret = __ENV.LT_INTAKE_SECRET || "dev-intake-secret";
const s = JSON.parse(open("./.session.json"));
const template = open("../frontend/e2e/fixtures/xrechnung-beispiel.xml");

export const options = {
  scenarios: {
    mails: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.LT_MAILS_PER_HOUR || 1000),
      timeUnit: "1h",
      duration: __ENV.LT_DURATION || "10m",
      preAllocatedVUs: 5,
      maxVUs: 20,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    // Accepting a mail must be quick; the work happens in the worker.
    http_req_duration: ["p(95)<2000"],
  },
};

export default function () {
  const number = `LT-MAIL-${__VU}-${__ITER}-${Date.now()}`;
  const xml = template.replace("<cbc:ID>RE-2026-0001</cbc:ID>", `<cbc:ID>${number}</cbc:ID>`);
  const boundary = `lt-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const mail =
    `From: rechnung@lieferant.example\r\nTo: ${s.address}\r\nSubject: Rechnung ${number}\r\nMIME-Version: 1.0\r\n` +
    `Content-Type: multipart/mixed; boundary="${boundary}"\r\n\r\n` +
    `--${boundary}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nAnbei die Rechnung.\r\n` +
    `--${boundary}\r\nContent-Type: application/xml; name="rechnung.xml"\r\nContent-Disposition: attachment; filename="rechnung.xml"\r\n` +
    `Content-Transfer-Encoding: base64\r\n\r\n${encoding.b64encode(xml).replace(/.{76}/g, "$&\r\n")}\r\n--${boundary}--\r\n`;
  const r = http.post(`${api}/internal/intake/mail`, mail, {
    headers: {
      "Content-Type": "message/rfc822",
      "X-Erika-Recipient": s.address,
      "X-Erika-Sender": "rechnung@lieferant.example",
      "X-Intake-Secret": secret,
    },
  });
  check(r, { accepted: (res) => res.status >= 200 && res.status < 300 });
}
