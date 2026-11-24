// Prepares a load test against a TEST stack (never production): creates an account through the
// API (email link from Mailpit, 2FA enrolment), uploads one real invoice as the view template,
// and prints the session cookie, the workspace ID and the intake address for k6.
//
//   node loadtest/prepare.mjs > loadtest/.session.json
//
// Environment: LT_BASE_URL (default http://localhost:3000), LT_MAILPIT (http://localhost:8025).
import { createHmac, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const base = process.env.LT_BASE_URL ?? "http://localhost:3000";
const mailpit = process.env.LT_MAILPIT ?? "http://localhost:8025";
const jar = new Map();
const ip = `10.95.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;

async function call(method, path, body, headers = {}) {
  const cookie = [...jar].map(([k, v]) => `${k}=${v}`).join("; ");
  const xsrf = jar.get("XSRF-TOKEN");
  const response = await fetch(base + path, {
    method,
    redirect: "manual",
    headers: {
      "X-Forwarded-For": ip,
      ...(cookie ? { cookie } : {}),
      ...(xsrf && method !== "GET" ? { "X-XSRF-TOKEN": decodeURIComponent(xsrf) } : {}),
      ...(body && !(body instanceof Uint8Array) ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: body ? (body instanceof Uint8Array ? body : JSON.stringify(body)) : undefined,
  });
  for (const c of response.headers.getSetCookie()) {
    const [pair] = c.split(";");
    const [k, ...v] = pair.split("=");
    jar.set(k.trim(), v.join("="));
  }
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${text}`);
  return text ? JSON.parse(text) : null;
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

async function token(email) {
  for (let i = 0; i < 60; i++) {
    const { messages } = await (await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`)).json();
    if (messages.length) {
      const message = await (await fetch(`${mailpit}/api/v1/message/${messages[0].ID}`)).json();
      const t = /token=([A-Za-z0-9_-]+)/.exec(message.Text)?.[1];
      if (t) return t;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error("no verification mail");
}

const email = `lasttest-${randomUUID().slice(0, 8)}@example.test`;
const password = "ein langer Satz nur für den Lasttest";
await call("GET", "/api/v1/auth/session");
await call("POST", "/api/v1/auth/signup", {
  email,
  password,
  displayName: "Lasttest",
  workspaceName: "Lasttest GmbH",
  termsAccepted: true,
});
await call("POST", "/api/v1/auth/verify-email", { token: await token(email) });
await call("POST", "/api/v1/auth/login", { email, password });
const enrolment = await call("POST", "/api/v1/auth/totp/enrolment");
await call("POST", "/api/v1/auth/totp/enrolment/confirm", { code: totp(enrolment.secret) });

// One real invoice: its view JSON is the template for the seeded documents.
const xml = readFileSync(new URL("../frontend/e2e/fixtures/xrechnung-beispiel.xml", import.meta.url));
const upload = await call("POST", "/api/v1/uploads", { fileName: "vorlage.xml", size: xml.length, contentType: "application/xml" });
const put = await fetch(upload.uploadUrl, { method: "PUT", headers: upload.uploadHeaders, body: xml });
if (!put.ok) throw new Error(`upload PUT ${put.status}`);
await call("POST", `/api/v1/uploads/${upload.id}/complete`);
for (let i = 0; i < 60; i++) {
  const u = await call("GET", `/api/v1/uploads/${upload.id}`);
  if (u.items?.[0]?.documentId) {
    const doc = await call("GET", `/api/v1/documents/${u.items[0].documentId}`);
    if (doc.processing === "DONE") break;
  }
  await new Promise((r) => setTimeout(r, 1000));
}
const session = await call("GET", "/api/v1/auth/session");
const address = (await call("GET", "/api/v1/intake/address")).address;
console.log(
  JSON.stringify(
    { session: jar.get("ERIKA_SESSION"), xsrf: jar.get("XSRF-TOKEN"), workspaceId: session.workspace.id, address, ip },
    null,
    2,
  ),
);
