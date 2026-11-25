// Creates a demo account with realistic, invented data on a LOCAL or TEST stack (never production).
// Everything goes through the public API, so archive, audit log, validation and numbering behave
// exactly as for a real user.
//
//   node demo/seed-demo.mjs
//
// Environment (all optional):
//   DEMO_BASE_URL   web app that forwards /api/v1     (default http://localhost:3000)
//   DEMO_MAILPIT    Mailpit web UI the back end uses   (default http://localhost:8025)
//   DEMO_EMAIL      owner's sign-in address            (default jana.brandt@brandt-haustechnik.example)
//   DEMO_PASSWORD   password for both demo accounts    (default below)
//
// The sign-in data (including the 2FA secret) is printed and written to demo/demo-account.local.json.
import { createHmac, randomUUID } from "node:crypto";
import { writeFileSync } from "node:fs";

const base = process.env.DEMO_BASE_URL ?? "http://localhost:3000";
const mailpit = process.env.DEMO_MAILPIT ?? "http://localhost:8025";
const ownerEmail = process.env.DEMO_EMAIL ?? "jana.brandt@brandt-haustechnik.example";
const advisorEmail = ownerEmail.replace(/^[^@]+@[^.]+/, "kanzlei@steuerberatung-albers");
const password = process.env.DEMO_PASSWORD ?? "Demo-Zugang für Brandt Haustechnik 2026";

// ---------------------------------------------------------------- helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const day = (offset) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
};
const firstOfNextMonth = () => {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)).toISOString().slice(0, 10);
};
const log = (msg) => console.error(msg);
const warnings = [];

/** A non-essential step: a failure is reported, the script goes on. */
async function step(name, fn) {
  try {
    const result = await fn();
    log(`  ✓ ${name}`);
    return result;
  } catch (e) {
    log(`  ✗ ${name}: ${e.message}`);
    warnings.push(`${name}: ${e.message}`);
    return null;
  }
}

class Client {
  constructor() {
    this.jar = new Map();
    // A separate client address per account, so local sign-in rate limits don't interfere.
    this.ip = `10.96.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;
  }

  async call(method, path, body, headers = {}) {
    const cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ");
    const xsrf = this.jar.get("XSRF-TOKEN");
    const response = await fetch(base + "/api/v1" + path, {
      method,
      redirect: "manual",
      headers: {
        "X-Forwarded-For": this.ip,
        ...(cookie ? { cookie } : {}),
        ...(xsrf && method !== "GET" ? { "X-XSRF-TOKEN": decodeURIComponent(xsrf) } : {}),
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(method === "POST" ? { "Idempotency-Key": randomUUID() } : {}),
        ...headers,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    for (const c of response.headers.getSetCookie()) {
      const [pair] = c.split(";");
      const [k, ...v] = pair.split("=");
      this.jar.set(k.trim(), v.join("="));
    }
    const text = await response.text();
    if (!response.ok) {
      let detail = text;
      try {
        const p = JSON.parse(text);
        detail = `${p.code ?? ""} ${p.detail ?? p.title ?? ""}`.trim();
      } catch {}
      throw new Error(`${method} ${path}: ${response.status} ${detail}`);
    }
    const data = text ? JSON.parse(text) : null;
    return { data, etag: response.headers.get("etag") };
  }

  get = async (path) => (await this.call("GET", path)).data;
  post = async (path, body = {}) => (await this.call("POST", path, body)).data;
  patch = async (path, body) => (await this.call("PATCH", path, body)).data;

  /** GET + PUT with If-Match (settings, drafts). */
  async put(path, body, etag) {
    const tag = etag ?? (await this.call("GET", path)).etag;
    return (await this.call("PUT", path, body, { "If-Match": tag })).data;
  }

  async signUpAndEnrol(email, signup) {
    await this.call("GET", "/auth/session");
    const since = Date.now();
    await this.post("/auth/signup", { email, password, locale: "de", termsAccepted: true, ...signup });
    const token = await mailToken(email, "/verify-email?token=", since);
    try {
      await this.post("/auth/verify-email", { token });
    } catch (e) {
      throw new Error(
        `${e.message}\n\nDoes ${email} exist already? Reset the local database ` +
          "(docker compose -f infra/local/compose.yml down -v) or choose another DEMO_EMAIL.",
      );
    }
    await this.post("/auth/login", { email, password });
    const enrolment = await this.post("/auth/totp/enrolment");
    const recovery = await this.post("/auth/totp/enrolment/confirm", { code: totp(enrolment.secret) });
    return { secret: enrolment.secret, otpauth: enrolment.otpauthUri, recoveryCodes: recovery.codes };
  }
}

function totp(secret) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/=+$/, "")) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

/** The token from the newest email to `email` whose text contains `marker`. */
async function mailToken(email, marker, since) {
  for (let i = 0; i < 90; i++) {
    try {
      const res = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
      const { messages = [] } = await res.json();
      for (const m of messages) {
        if (new Date(m.Created).getTime() < since - 5000) continue;
        const message = await (await fetch(`${mailpit}/api/v1/message/${m.ID}`)).json();
        const at = message.Text.indexOf(marker);
        if (at >= 0) return /token=([A-Za-z0-9_-]+)/.exec(message.Text.slice(at))[1];
      }
    } catch (e) {
      if (i === 0) log(`    (Mailpit at ${mailpit} not reachable yet: ${e.message})`);
    }
    await sleep(1000);
  }
  throw new Error(
    `No email to ${email} in Mailpit (${mailpit}). Is the worker running, and is DEMO_MAILPIT ` +
      "the Mailpit your back end sends to?",
  );
}

// ---------------------------------------------------------------- invented data

const company = {
  name: "Brandt Haustechnik GmbH",
  street: "Kesselgasse 12",
  postalCode: "04109",
  city: "Leipzig",
  countryCode: "DE",
  vatId: "DE123456789",
  taxNumber: "232/118/04711",
};

const suppliers = {
  sanitaer: {
    name: "Sanitär-Großhandel Nord GmbH",
    street: "Rohrstraße 40",
    postalCode: "04155",
    city: "Leipzig",
    vatId: "DE811111111",
    email: "rechnung@sanitaer-nord.example",
    contact: "Petra Lange",
    phone: "+49 341 5550100",
    iban: "DE02120300000000202051",
  },
  elektro: {
    name: "Elektro Fischer KG",
    street: "Am Umspannwerk 3",
    postalCode: "04229",
    city: "Leipzig",
    vatId: "DE822222222",
    email: "buchhaltung@elektro-fischer.example",
    contact: "Uwe Fischer",
    phone: "+49 341 5550200",
    iban: "DE02100500000054540402",
  },
  werkzeug: {
    name: "Werkzeug Mayer e.K.",
    street: "Industriestraße 8",
    postalCode: "04420",
    city: "Markranstädt",
    vatId: "DE833333333",
    email: "rechnungen@werkzeug-mayer.example",
    contact: "Sabine Mayer",
    phone: "+49 34205 55030",
    iban: "DE02300209000106531065",
  },
  buero: {
    name: "Bürobedarf Klein OHG",
    street: "Papierweg 21",
    postalCode: "04103",
    city: "Leipzig",
    vatId: "DE844444444",
    email: "service@buerobedarf-klein.example",
    contact: "Mehmet Klein",
    phone: "+49 341 5550400",
    iban: "DE02120300000000202051",
  },
  baustoffe: {
    name: "Baustoffe Weber GmbH",
    street: "Kiesgrubenweg 5",
    postalCode: "04319",
    city: "Leipzig",
    vatId: "DE855555555",
    email: "faktura@baustoffe-weber.example",
    contact: "Lars Weber",
    phone: "+49 341 5550500",
    iban: "DE02100500000054540402",
  },
  autohaus: {
    name: "Autohaus Lindner GmbH",
    street: "Werkstattring 2",
    postalCode: "04179",
    city: "Leipzig",
    vatId: "DE866666666",
    email: "service@autohaus-lindner.example",
    contact: "Kerstin Lindner",
    phone: "+49 341 5550600",
    iban: "DE02300209000106531065",
  },
};

// ---------------------------------------------------------------- XRechnung 3.0 (UBL) generator

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const cents = (n) => Math.round(n * 100);
const amt = (c) => (c / 100).toFixed(2);

function xrechnung({ supplier, number, issueDate, dueDate, deliveryDate, buyerReference, lines, breakTotals = false }) {
  const lineData = lines.map((l, i) => ({ ...l, id: i + 1, net: cents(l.qty * l.price) }));
  const groups = new Map();
  for (const l of lineData) groups.set(l.rate, (groups.get(l.rate) ?? 0) + l.net);
  const net = lineData.reduce((s, l) => s + l.net, 0);
  const subtotals = [...groups].map(([rate, base]) => ({ rate, base, tax: Math.round((base * rate) / 100) }));
  const tax = subtotals.reduce((s, t) => s + t.tax, 0);
  const gross = net + tax;
  // A deliberately wrong total (sum of lines ≠ invoice total) for the "red" example.
  const payable = breakTotals ? gross + 10000 : gross;
  const taxCategory = (rate) =>
    `<cbc:ID>S</cbc:ID><cbc:Percent>${rate}</cbc:Percent><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>`;
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<!-- Invented demo data (ERIKA demo/seed-demo.mjs). -->
<ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
             xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
             xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
  <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0</cbc:CustomizationID>
  <cbc:ProfileID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</cbc:ProfileID>
  <cbc:ID>${esc(number)}</cbc:ID>
  <cbc:IssueDate>${issueDate}</cbc:IssueDate>
  <cbc:DueDate>${dueDate}</cbc:DueDate>
  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>
  <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
  <cbc:BuyerReference>${esc(buyerReference)}</cbc:BuyerReference>
  <cac:AccountingSupplierParty>
    <cac:Party>
      <cbc:EndpointID schemeID="EM">${esc(supplier.email)}</cbc:EndpointID>
      <cac:PartyName><cbc:Name>${esc(supplier.name)}</cbc:Name></cac:PartyName>
      <cac:PostalAddress>
        <cbc:StreetName>${esc(supplier.street)}</cbc:StreetName>
        <cbc:CityName>${esc(supplier.city)}</cbc:CityName>
        <cbc:PostalZone>${supplier.postalCode}</cbc:PostalZone>
        <cac:Country><cbc:IdentificationCode>DE</cbc:IdentificationCode></cac:Country>
      </cac:PostalAddress>
      <cac:PartyTaxScheme>
        <cbc:CompanyID>${supplier.vatId}</cbc:CompanyID>
        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>
      </cac:PartyTaxScheme>
      <cac:PartyLegalEntity><cbc:RegistrationName>${esc(supplier.name)}</cbc:RegistrationName></cac:PartyLegalEntity>
      <cac:Contact>
        <cbc:Name>${esc(supplier.contact)}</cbc:Name>
        <cbc:Telephone>${esc(supplier.phone)}</cbc:Telephone>
        <cbc:ElectronicMail>${esc(supplier.email)}</cbc:ElectronicMail>
      </cac:Contact>
    </cac:Party>
  </cac:AccountingSupplierParty>
  <cac:AccountingCustomerParty>
    <cac:Party>
      <cbc:EndpointID schemeID="EM">${esc(ownerEmail)}</cbc:EndpointID>
      <cac:PostalAddress>
        <cbc:StreetName>${esc(company.street)}</cbc:StreetName>
        <cbc:CityName>${esc(company.city)}</cbc:CityName>
        <cbc:PostalZone>${company.postalCode}</cbc:PostalZone>
        <cac:Country><cbc:IdentificationCode>DE</cbc:IdentificationCode></cac:Country>
      </cac:PostalAddress>
      <cac:PartyLegalEntity><cbc:RegistrationName>${esc(company.name)}</cbc:RegistrationName></cac:PartyLegalEntity>
    </cac:Party>
  </cac:AccountingCustomerParty>
  <cac:Delivery><cbc:ActualDeliveryDate>${deliveryDate}</cbc:ActualDeliveryDate></cac:Delivery>
  <cac:PaymentMeans>
    <cbc:PaymentMeansCode>58</cbc:PaymentMeansCode>
    <cac:PayeeFinancialAccount><cbc:ID>${supplier.iban}</cbc:ID></cac:PayeeFinancialAccount>
  </cac:PaymentMeans>
  <cac:PaymentTerms><cbc:Note>Zahlbar bis ${dueDate.split("-").reverse().join(".")} ohne Abzug.</cbc:Note></cac:PaymentTerms>
  <cac:TaxTotal>
    <cbc:TaxAmount currencyID="EUR">${amt(tax)}</cbc:TaxAmount>
${subtotals
  .map(
    (t) => `    <cac:TaxSubtotal>
      <cbc:TaxableAmount currencyID="EUR">${amt(t.base)}</cbc:TaxableAmount>
      <cbc:TaxAmount currencyID="EUR">${amt(t.tax)}</cbc:TaxAmount>
      <cac:TaxCategory>${taxCategory(t.rate)}</cac:TaxCategory>
    </cac:TaxSubtotal>`,
  )
  .join("\n")}
  </cac:TaxTotal>
  <cac:LegalMonetaryTotal>
    <cbc:LineExtensionAmount currencyID="EUR">${amt(net)}</cbc:LineExtensionAmount>
    <cbc:TaxExclusiveAmount currencyID="EUR">${amt(net)}</cbc:TaxExclusiveAmount>
    <cbc:TaxInclusiveAmount currencyID="EUR">${amt(payable)}</cbc:TaxInclusiveAmount>
    <cbc:PayableAmount currencyID="EUR">${amt(payable)}</cbc:PayableAmount>
  </cac:LegalMonetaryTotal>
${lineData
  .map(
    (l) => `  <cac:InvoiceLine>
    <cbc:ID>${l.id}</cbc:ID>
    <cbc:InvoicedQuantity unitCode="${l.unit}">${l.qty}</cbc:InvoicedQuantity>
    <cbc:LineExtensionAmount currencyID="EUR">${amt(l.net)}</cbc:LineExtensionAmount>
    <cac:Item>
      <cbc:Name>${esc(l.name)}</cbc:Name>
      <cac:ClassifiedTaxCategory>${taxCategory(l.rate)}</cac:ClassifiedTaxCategory>
    </cac:Item>
    <cac:Price><cbc:PriceAmount currencyID="EUR">${l.price.toFixed(2)}</cbc:PriceAmount></cac:Price>
  </cac:InvoiceLine>`,
  )
  .join("\n")}
</ubl:Invoice>
`;
  return Buffer.from(xml, "utf-8");
}

/** A small plain PDF (no e-invoice inside), e.g. a scanned paper invoice. */
function plainPdf(lines) {
  const text = lines
    .map((l, i) => `BT /F1 ${i === 0 ? 16 : 11} Tf 60 ${770 - i * 22} Td (${l.replace(/[()\\]/g, "\\$&")}) Tj ET`)
    .join("\n");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    `<< /Length ${Buffer.byteLength(text, "latin1")} >>\nstream\n${text}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
  ];
  let out = "%PDF-1.4\n";
  const offsets = [];
  objects.forEach((o, i) => {
    offsets.push(Buffer.byteLength(out, "latin1"));
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = Buffer.byteLength(out, "latin1");
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) out += `${String(o).padStart(10, "0")} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, "latin1");
}

async function upload(client, fileName, bytes, contentType) {
  const u = await client.post("/uploads", { fileName, size: bytes.length, contentType });
  const put = await fetch(u.uploadUrl, { method: "PUT", headers: u.uploadHeaders, body: bytes });
  if (!put.ok) throw new Error(`upload PUT ${put.status} (is the S3 store on ${new URL(u.uploadUrl).host} reachable?)`);
  await client.post(`/uploads/${u.id}/complete`);
  for (let i = 0; i < 120; i++) {
    const s = await client.get(`/uploads/${u.id}`);
    const documentId = s.items?.[0]?.documentId;
    if (documentId) {
      const doc = await client.get(`/documents/${documentId}`);
      if (doc.processing === "DONE" || doc.processing === "FAILED") return doc;
    }
    if (s.status === "REJECTED" || s.status === "FAILED") throw new Error(`upload ${s.status} ${s.reason ?? ""}`);
    await sleep(1000);
  }
  throw new Error("not processed after 2 minutes: is the worker running, and ClamAV ready?");
}

async function issue(client, invoiceId) {
  await client.post(`/invoices/${invoiceId}/issue`);
  for (let i = 0; i < 90; i++) {
    const inv = await client.get(`/invoices/${invoiceId}`);
    if (inv.state === "ISSUED") return inv;
    if (inv.state === "DRAFT" && inv.issueError?.length)
      throw new Error(`not issued: ${inv.issueError.map((m) => `${m.ruleId} ${m.text}`).join(", ")}`);
    await sleep(1000);
  }
  throw new Error("still being issued after 90 s: is the worker running?");
}

async function draft(client, { allowProblems = false, ...content }) {
  const { data, etag } = await client.call("POST", "/invoices", { format: content.format, lines: [] });
  const saved = await client.put(`/invoices/${data.id}`, content, etag);
  if (saved.problems?.length && !allowProblems)
    throw new Error(`draft incomplete: ${saved.problems.map((p) => `${p.field} ${p.code}`).join(", ")}`);
  return saved;
}

// ---------------------------------------------------------------- run

log(`ERIKA demo data → ${base} (Mailpit ${mailpit})\n`);
try {
  const health = await fetch(`${base}/api/v1/auth/session`);
  if (!health.ok) throw new Error(`HTTP ${health.status}`);
} catch (e) {
  log(`The web app at ${base} doesn't answer (${e.message}). Start the stack first (docs/running-locally.md).`);
  process.exit(1);
}

const owner = new Client();
log("1. Owner account");
const ownerTotp = await owner.signUpAndEnrol(ownerEmail, {
  displayName: "Jana Brandt",
  workspaceName: company.name,
});
log(`  ✓ ${ownerEmail} signed up, email confirmed, 2FA set up`);

log("2. Company and invoice settings");
await step("company data", () => owner.put("/workspace", company));
await step("invoice settings", () =>
  owner.put("/invoice-settings", {
    contactName: "Jana Brandt",
    contactPhone: "+49 341 5559876",
    contactEmail: "rechnung@brandt-haustechnik.example",
    legalRegistration: "HRB 99999, Amtsgericht Leipzig",
    iban: "DE89370400440532013000",
    bic: "COBADEFFXXX",
    accountHolder: company.name,
    kleinunternehmer: false,
    numberPattern: "RE-{YYYY}-{NNNN}",
    creditNotePattern: "GS-{YYYY}-{NNNN}",
    defaultFormat: "ZUGFERD_EN16931",
    paymentTermsDays: 14,
    accentColor: "#1F6F8B",
  }),
);

log("3. Incoming invoices (upload, virus scan, validation)");
const incoming = [
  {
    file: "SGN-2026-10877.xml",
    xml: {
      supplier: suppliers.sanitaer,
      number: "SGN-2026-10877",
      issueDate: day(-34),
      dueDate: day(-20),
      deliveryDate: day(-36),
      buyerReference: "Kunden-Nr. 40211",
      lines: [
        { name: "Badheizkörper Weiß 1200×600", qty: 2, unit: "C62", price: 289.0, rate: 19 },
        { name: "Thermostatventil Eckform", qty: 6, unit: "C62", price: 18.4, rate: 19 },
        { name: "Pressfitting Kupfer 22 mm", qty: 40, unit: "C62", price: 3.15, rate: 19 },
      ],
    },
    meta: [
      { status: "CHECKED", statusNote: "Lieferung vollständig" },
      { status: "APPROVED" },
      { status: "PAID", statusNote: "Überwiesen", tags: ["Material", "Baustelle Petersen"] },
    ],
  },
  {
    file: "EF-26-3391.xml",
    xml: {
      supplier: suppliers.elektro,
      number: "EF-26-3391",
      issueDate: day(-12),
      dueDate: day(16),
      deliveryDate: day(-14),
      buyerReference: "Auftrag Hotel Seeblick",
      lines: [
        { name: "Elektroanschluss Wärmepumpe", qty: 6, unit: "HUR", price: 62.0, rate: 19 },
        { name: "Leitungsschutzschalter B16", qty: 4, unit: "C62", price: 9.8, rate: 19 },
      ],
    },
    meta: [{ status: "CHECKED" }, { status: "APPROVED", statusNote: "Freigegeben", tags: ["Subunternehmer"] }],
  },
  {
    file: "WM-118204.xml",
    xml: {
      supplier: suppliers.werkzeug,
      number: "WM-118204",
      issueDate: day(-12),
      dueDate: day(2),
      deliveryDate: day(-13),
      buyerReference: "Kd. 7781",
      lines: [
        { name: "Akku-Pressmaschine 18 V", qty: 1, unit: "C62", price: 1149.0, rate: 19 },
        { name: "Pressbacken-Set V 15–28", qty: 1, unit: "C62", price: 389.0, rate: 19 },
      ],
    },
    meta: [{ status: "CHECKED", tags: ["Werkzeug"], note: "Fällig in 2 Tagen – Skonto prüfen." }],
  },
  {
    file: "BK-2026-5512.xml",
    xml: {
      supplier: suppliers.buero,
      number: "BK-2026-5512",
      issueDate: day(-6),
      dueDate: day(8),
      deliveryDate: day(-6),
      buyerReference: "Büro",
      lines: [
        { name: "Kopierpapier A4, 5 × 500 Blatt", qty: 2, unit: "C62", price: 24.9, rate: 19 },
        { name: "Fachbuch Trinkwasserinstallation", qty: 1, unit: "C62", price: 49.0, rate: 7 },
      ],
    },
    meta: [{ tags: ["Büro"] }],
  },
  {
    file: "BW-RE-24-0981.xml",
    xml: {
      supplier: suppliers.baustoffe,
      number: "BW-RE-24-0981",
      issueDate: day(-4),
      dueDate: day(10),
      deliveryDate: day(-5),
      buyerReference: "Baustelle Keller",
      lines: [
        { name: "Estrichbeton 40 kg", qty: 30, unit: "C62", price: 6.9, rate: 19 },
        { name: "Anlieferung Kran", qty: 1, unit: "C62", price: 85.0, rate: 19 },
      ],
      breakTotals: true,
    },
    meta: [
      {
        status: "DISPUTED",
        statusNote: "Gesamtbetrag stimmt nicht mit den Positionen überein",
        tags: ["Material", "Baustelle Keller"],
      },
    ],
    comment: "Summe ist 100 € zu hoch – bei Weber eine korrigierte Rechnung angefordert.",
  },
  {
    file: "Werkstattrechnung-Lindner-0412.pdf",
    pdf: [
      "Autohaus Lindner GmbH - Werkstattrechnung 0412",
      `Datum: ${day(-2).split("-").reverse().join(".")}`,
      `An: ${company.name}, ${company.street}, ${company.postalCode} ${company.city}`,
      "Inspektion Transporter L-BH 2210                 189,00 EUR",
      "Wischerblaetter                                    24,90 EUR",
      "Netto 213,90 EUR  USt 19 % 40,64 EUR  Gesamt 254,54 EUR",
      "(Papierrechnung, eingescannt - keine E-Rechnung)",
    ],
    meta: [{ tags: ["Fahrzeug"] }],
  },
];

const docs = {};
for (const inv of incoming) {
  const doc = await step(`upload ${inv.file}`, () =>
    inv.pdf
      ? upload(owner, inv.file, plainPdf(inv.pdf), "application/pdf")
      : upload(owner, inv.file, xrechnung(inv.xml), "application/xml"),
  );
  if (!doc) continue;
  docs[inv.file] = doc;
  for (const m of inv.meta ?? []) await step(`  ${inv.file}: ${m.status ?? "tags/note"}`, () => owner.patch(`/documents/${doc.id}`, m));
  if (inv.comment) await step(`  ${inv.file}: comment`, () => owner.post(`/documents/${doc.id}/comments`, { body: inv.comment }));
}
await step("upload EF-26-3391 (2).xml: the same file again (ERIKA recognises it)", () =>
  upload(owner, "EF-26-3391 (2).xml", xrechnung(incoming[1].xml), "application/xml"),
);

log("4. Customers and articles");
const customerInput = [
  {
    customerNumber: "K-1001",
    name: "Wohnbau Petersen GmbH",
    street: "Lindenauer Markt 9",
    postalCode: "04177",
    city: "Leipzig",
    countryCode: "DE",
    vatId: "DE877777777",
    email: "rechnungseingang@wohnbau-petersen.example",
    contactName: "Frank Petersen",
    buyerReference: "PO-2026-118",
    vatTreatment: "DOMESTIC",
    preferredFormat: "XRECHNUNG_CII",
    paymentTermsDays: 30,
  },
  {
    customerNumber: "K-1002",
    name: "Familie Keller",
    street: "Birkenweg 4",
    postalCode: "04425",
    city: "Taucha",
    countryCode: "DE",
    email: "keller.familie@mail.example",
    contactName: "Andrea Keller",
    vatTreatment: "DOMESTIC",
    preferredFormat: "ZUGFERD_EN16931",
  },
  {
    customerNumber: "K-1003",
    name: "Hotel Seeblick GmbH & Co. KG",
    street: "Uferpromenade 1",
    postalCode: "04416",
    city: "Markkleeberg",
    countryCode: "DE",
    vatId: "DE888888888",
    email: "buchhaltung@hotel-seeblick.example",
    contactName: "Claudia Roth",
    buyerReference: "Wartungsvertrag 2026",
    vatTreatment: "DOMESTIC",
    preferredFormat: "ZUGFERD_EN16931",
  },
  {
    customerNumber: "K-1004",
    name: "Stadt Beispielhausen – Hochbauamt",
    street: "Rathausplatz 1",
    postalCode: "04600",
    city: "Beispielhausen",
    countryCode: "DE",
    email: "erechnung@beispielhausen.example",
    buyerReference: "14612000-0815-42",
    vatTreatment: "DOMESTIC",
    preferredFormat: "XRECHNUNG_UBL",
  },
];
const customers = {};
for (const c of customerInput) {
  const created = await step(`customer ${c.name}`, () => owner.post("/customers", c));
  if (created) customers[c.customerNumber] = created;
}
const articleInput = [
  { name: "Monteurstunde", articleNumber: "L-01", unitCode: "HUR", netPrice: "68.00", vatRate: "STANDARD_19" },
  { name: "Helferstunde", articleNumber: "L-02", unitCode: "HUR", netPrice: "48.00", vatRate: "STANDARD_19" },
  { name: "Anfahrtspauschale", articleNumber: "P-01", unitCode: "C62", netPrice: "35.00", vatRate: "STANDARD_19" },
  { name: "Wartung Gas-Brennwertkessel", articleNumber: "W-01", unitCode: "C62", netPrice: "149.00", vatRate: "STANDARD_19" },
  { name: "Thermostatventil Eckform", articleNumber: "M-10", unitCode: "C62", netPrice: "24.90", vatRate: "STANDARD_19" },
  { name: "Kleinmaterial pauschal", articleNumber: "M-99", unitCode: "C62", netPrice: "19.50", vatRate: "STANDARD_19" },
];
const articles = {};
for (const a of articleInput) {
  const created = await step(`article ${a.name}`, () => owner.post("/articles", a));
  if (created) articles[a.articleNumber] = created;
}
const line = (no, quantity, extra = {}) => {
  const a = articleInput.find((x) => x.articleNumber === no);
  return {
    articleId: articles[no]?.id,
    articleNumber: a.articleNumber,
    name: a.name,
    unitCode: a.unitCode,
    netPrice: a.netPrice,
    vatRate: a.vatRate,
    quantity: String(quantity),
    ...extra,
  };
};
const buyerOf = (no) => {
  const c = customerInput.find((x) => x.customerNumber === no);
  const { customerNumber, name, street, postalCode, city, countryCode, vatId, email, contactName } = c;
  return {
    customerId: customers[no]?.id,
    buyer: { customerNumber, name, street, postalCode, city, countryCode, vatId, email, contactName },
    buyerReference: c.buyerReference,
    vatTreatment: c.vatTreatment,
    format: c.preferredFormat,
  };
};

log("5. Outgoing invoices (issue, send, cancel, recurring)");
const petersen = await step("invoice Wohnbau Petersen: issue (XRechnung CII)", async () => {
  const d = await draft(owner, {
    ...buyerOf("K-1001"),
    periodStart: day(-21),
    periodEnd: day(-7),
    orderReference: "PO-2026-118",
    note: "Badsanierung Wohnung 3.2, Lindenauer Markt 9.",
    lines: [
      line("L-01", 22),
      line("L-02", 14),
      line("M-10", 6),
      { name: "Badheizkörper Weiß 1200×600", quantity: "2", unitCode: "C62", netPrice: "395.00", vatRate: "STANDARD_19" },
      line("P-01", 4),
    ],
  });
  return issue(owner, d.id);
});
if (petersen) {
  await step("  send to Wohnbau Petersen", () =>
    owner.post(`/invoices/${petersen.id}/send`, {
      to: customerInput[0].email,
      message: "Guten Tag Herr Petersen,\n\nanbei unsere Rechnung für die Badsanierung.\n\nViele Grüße\nJana Brandt",
    }),
  );
}

const keller = await step("invoice Familie Keller: issue (ZUGFeRD)", async () => {
  const d = await draft(owner, {
    ...buyerOf("K-1002"),
    deliveryDate: day(-9),
    note: "Jährliche Wartung der Gasheizung.",
    lines: [line("W-01", 1), line("P-01", 1), line("M-99", 1)],
  });
  return issue(owner, d.id);
});
if (keller) {
  await step("  send to Familie Keller", () =>
    owner.post(`/invoices/${keller.id}/send`, {
      to: customerInput[1].email,
      message: "Liebe Familie Keller,\n\nvielen Dank für Ihren Auftrag. Anbei die Rechnung.\n\nViele Grüße\nBrandt Haustechnik",
    }),
  );
  await sleep(3000);
  await step("  mark as paid", () =>
    owner.patch(`/documents/${keller.issued.documentId}`, { status: "PAID", statusNote: "Zahlungseingang Sparkasse" }),
  );
}

const seeblick = await step("invoice Hotel Seeblick: issue (monthly maintenance)", async () => {
  const d = await draft(owner, {
    ...buyerOf("K-1003"),
    periodStart: day(-30),
    periodEnd: day(-1),
    note: "Wartungsvertrag Heizungs- und Sanitäranlagen, monatliche Pauschale.",
    lines: [
      { name: "Wartungspauschale Heizung und Sanitär", quantity: "1", unitCode: "MON", netPrice: "420.00", vatRate: "STANDARD_19" },
    ],
  });
  return issue(owner, d.id);
});
if (seeblick) {
  await step("  make it recur monthly", () =>
    owner.post("/recurring-invoices", {
      sourceInvoiceId: seeblick.id,
      name: "Wartungsvertrag Hotel Seeblick",
      interval: "MONTHLY",
      nextRun: firstOfNextMonth(),
    }),
  );
}

await step("invoice Stadt Beispielhausen: issue (XRechnung UBL, Leitweg-ID)", async () => {
  const d = await draft(owner, {
    ...buyerOf("K-1004"),
    deliveryDate: day(-3),
    orderReference: "HBA-2026-77",
    note: "Austausch Trinkwasser-Durchlauferhitzer, Grundschule Am Park.",
    lines: [
      line("L-01", 5),
      { name: "Elektro-Durchlauferhitzer 21 kW", quantity: "2", unitCode: "C62", netPrice: "468.00", vatRate: "STANDARD_19" },
      line("P-01", 1),
    ],
  });
  return issue(owner, d.id);
});

await step("invoice issued by mistake, then cancelled", async () => {
  const d = await draft(owner, {
    ...buyerOf("K-1003"),
    deliveryDate: day(-1),
    note: "Notdienst Wasserschaden Zimmer 12.",
    lines: [line("L-01", 30), line("P-01", 1)],
  });
  const wrong = await issue(owner, d.id);
  const { data } = await owner.call("POST", `/invoices/${wrong.id}/credit-note`, { kind: "CANCELLATION" });
  return issue(owner, data.id);
});

await step("open draft for Wohnbau Petersen (not finished)", () =>
  draft(owner, {
    ...buyerOf("K-1001"),
    note: "Heizungstausch Haus B – Angebot angenommen, Abrechnung folgt.",
    lines: [line("L-01", 16), line("L-02", 16)],
    allowProblems: true,
  }),
);

log("6. Team: tax advisor and a colleague");
let advisor = null;
await step("invite tax advisor (ADVISOR)", async () => {
  const since = Date.now();
  await owner.post("/invitations", { email: advisorEmail, role: "ADVISOR" });
  const token = await mailToken(advisorEmail, "/invitations/accept?token=", since);
  const client = new Client();
  advisor = await client.signUpAndEnrol(advisorEmail, { displayName: "Dr. Henrike Albers", workspaceInvitation: token });
  const disputed = docs["BW-RE-24-0981.xml"];
  if (disputed)
    await client.post(`/documents/${disputed.id}/comments`, {
      body: "Bitte erst nach Eingang der korrigierten Rechnung buchen. – H. Albers",
    });
});
await step("invite colleague Tom Brandt (MEMBER, invitation stays open)", () =>
  owner.post("/invitations", { email: ownerEmail.replace(/^[^@]+/, "tom.brandt"), role: "MEMBER" }),
);

// ---------------------------------------------------------------- result

const account = {
  url: `${base}/login`,
  mailpit,
  owner: {
    name: "Jana Brandt",
    company: company.name,
    email: ownerEmail,
    password,
    totpSecret: ownerTotp.secret,
    otpauth: ownerTotp.otpauth,
    recoveryCodes: ownerTotp.recoveryCodes,
  },
  advisor: advisor && {
    name: "Dr. Henrike Albers",
    email: advisorEmail,
    password,
    totpSecret: advisor.secret,
    otpauth: advisor.otpauth,
    recoveryCodes: advisor.recoveryCodes,
  },
  warnings,
};
const file = new URL("./demo-account.local.json", import.meta.url);
writeFileSync(file, JSON.stringify(account, null, 2) + "\n");

log(`
Done${warnings.length ? ` with ${warnings.length} warning(s) (see above)` : ""}.

Sign in at ${base}/login
  Owner:        ${ownerEmail}
  Tax advisor:  ${advisor ? advisorEmail : "(not created)"}
  Password:     ${password}
  2FA code:     node demo/totp.mjs          (or add the secret to an authenticator app)

Everything, including the 2FA secrets and recovery codes, is in demo/demo-account.local.json.
`);
