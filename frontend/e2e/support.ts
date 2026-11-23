import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { Socket } from "node:net";
import { crc32 } from "node:zlib";
import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];
export const mailpit = process.env.E2E_MAILPIT_URL ?? "http://localhost:8025";

export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(WCAG).analyze();
  expect(results.violations).toEqual([]);
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), as an authenticator app computes it. */
export function totp(base32Secret: string, offsetSteps = 0): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of base32Secret.replace(/=+$/, "")) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30) + offsetSteps));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1]! & 0x0f;
  const code = (hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return code.toString().padStart(6, "0");
}

/** Waits for the verification mail in Mailpit and returns the token from its link. */
export async function verificationToken(email: string): Promise<string> {
  for (let i = 0; i < 60; i++) {
    const search = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
    const { messages } = (await search.json()) as { messages: { ID: string }[] };
    if (messages.length > 0) {
      const message = await (await fetch(`${mailpit}/api/v1/message/${messages[0]!.ID}`)).json();
      const token = /token=([A-Za-z0-9_-]+)/.exec(message.Text as string)?.[1];
      if (token) return token;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`No verification mail for ${email}`);
}

/**
 * Creates an account through the API (sign-up, email link, password, 2FA enrolment) with
 * the page's own cookies, so the browser ends up signed in. The UI of these steps is
 * covered by auth.spec.ts.
 */
export async function signedInAccount(page: Page, prefix: string): Promise<{ email: string }> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`;
  const password = "ein langer Satz als Passwort";
  const api = page.request;
  // Sign-in steps are rate-limited per client IP; each test account gets its own simulated
  // IP (as the back-end tests do), so parallel tests don't lock each other out.
  const ip = `10.99.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;
  await api.get("/api/v1/auth/session", { headers: { "X-Forwarded-For": ip } });
  const csrf = async () => {
    const cookie = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN");
    return { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? ""), "X-Forwarded-For": ip };
  };
  const post = async (path: string, data?: unknown) => {
    const response = await api.post(path, { data, headers: await csrf() });
    expect(response.ok(), `${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  };
  await post("/api/v1/auth/signup", {
    email,
    password,
    displayName: "Erika Test",
    workspaceName: "Test Malerbetrieb",
    termsAccepted: true,
  });
  await post("/api/v1/auth/verify-email", { token: await verificationToken(email) });
  await post("/api/v1/auth/login", { email, password });
  const enrolment = await post("/api/v1/auth/totp/enrolment");
  await post("/api/v1/auth/totp/enrolment/confirm", { code: totp(enrolment.secret as string) });
  return { email };
}

/** The ERIKA sample XRechnung with its own invoice number (a new file each time). */
export function sampleInvoice(invoiceNumber: string): Buffer {
  const xml = readFileSync(new URL("./fixtures/xrechnung-beispiel.xml", import.meta.url), "utf-8");
  return Buffer.from(xml.replace("<cbc:ID>RE-2026-0001</cbc:ID>", `<cbc:ID>${invoiceNumber}</cbc:ID>`), "utf-8");
}

/** A minimal valid one-page PDF without embedded XML ("not an e-invoice", IN-04). */
export function plainPdf(title: string): Buffer {
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
    `<< /Title (${title}) >>`,
  ];
  let body = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((o, i) => {
    offsets.push(Buffer.byteLength(body, "latin1"));
    body += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = Buffer.byteLength(body, "latin1");
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  body += offsets.map((o) => `${String(o).padStart(10, "0")} 00000 n \n`).join("");
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R /Info 4 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, "latin1");
}

/** A ZIP (method "stored", no compression) of the given files. */
export function zip(entries: { name: string; data: Buffer }[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const fileName = Buffer.from(name, "utf-8");
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(fileName.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(fileName.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, fileName, data);
    centrals.push(central, fileName);
    offset += local.length + fileName.length + data.length;
  }
  const centralSize = centrals.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, end]);
}

/** The EICAR anti-virus test file, assembled at run time so no file in the repository contains it. */
export function eicar(): Buffer {
  return Buffer.from(
    ["X5O!P%@AP[4\\PZX54(P^)7CC)7}", "$EICAR-STANDARD-", "ANTIVIRUS-TEST-FILE!", "$H+H*"].join(""),
    "ascii",
  );
}

/** Sends an email with attachments over plain SMTP, as a supplier's mail server would. */
export async function sendMail(
  host: string,
  port: number,
  to: string,
  attachments: { name: string; type: string; data: Buffer }[],
): Promise<void> {
  const boundary = `erika-${Date.now()}`;
  const parts = [
    `--${boundary}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nAnbei unsere Rechnung.\r\n`,
    ...attachments.map(
      (a) =>
        `--${boundary}\r\nContent-Type: ${a.type}; name="${a.name}"\r\nContent-Disposition: attachment; filename="${a.name}"\r\n` +
        `Content-Transfer-Encoding: base64\r\n\r\n${a.data.toString("base64").replace(/.{76}/g, "$&\r\n")}\r\n`,
    ),
  ];
  const message =
    `From: rechnung@lieferant.example\r\nTo: ${to}\r\nSubject: Ihre Rechnung\r\nMIME-Version: 1.0\r\n` +
    `Content-Type: multipart/mixed; boundary="${boundary}"\r\n\r\n${parts.join("")}--${boundary}--\r\n`;

  const socket = new Socket();
  const replies: string[] = [];
  let waiting: ((line: string) => void) | null = null;
  let buffer = "";
  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf-8");
    let i;
    while ((i = buffer.indexOf("\r\n")) >= 0) {
      const line = buffer.slice(0, i);
      buffer = buffer.slice(i + 2);
      if (/^\d{3} /.test(line)) {
        if (waiting) {
          const w = waiting;
          waiting = null;
          w(line);
        } else replies.push(line);
      }
    }
  });
  const reply = () =>
    new Promise<string>((resolve) => (replies.length > 0 ? resolve(replies.shift()!) : (waiting = resolve)));
  const command = async (line: string, expected: string) => {
    socket.write(`${line}\r\n`);
    const answer = await reply();
    if (!answer.startsWith(expected)) throw new Error(`SMTP ${line.split(" ")[0]}: ${answer}`);
  };
  await new Promise<void>((resolve, reject) => {
    socket.once("error", reject);
    socket.connect(port, host, resolve);
  });
  const greeting = await reply();
  if (!greeting.startsWith("220")) throw new Error(`SMTP greeting: ${greeting}`);
  await command("EHLO lieferant.example", "250");
  await command("MAIL FROM:<rechnung@lieferant.example>", "250");
  await command(`RCPT TO:<${to}>`, "250");
  await command("DATA", "354");
  // Dot-stuffing: lines starting with "." get another one.
  await command(`${message.replace(/\r\n\./g, "\r\n..")}\r\n.`, "250");
  await command("QUIT", "221");
  socket.end();
}

/** A JSON API call with the page's session and CSRF token (for setting up test data). */
export async function apiCall(
  page: Page,
  method: "GET" | "POST" | "PUT",
  path: string,
  data?: unknown,
  headers: Record<string, string> = {},
) {
  const cookie = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN");
  const response = await page.request.fetch(path, {
    method,
    data,
    headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? ""), ...headers },
  });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return { body: await response.json().catch(() => null), etag: response.headers()["etag"] ?? "" };
}

/** Seller profile and invoice settings complete, so invoices can be issued (Stage 8). */
export async function completeSeller(page: Page) {
  const ws = await apiCall(page, "GET", "/api/v1/workspace");
  await apiCall(
    page,
    "PUT",
    "/api/v1/workspace",
    {
      name: ws.body.name,
      street: "Musterstraße 1",
      postalCode: "10115",
      city: "Berlin",
      countryCode: "DE",
      vatId: "DE123456789",
      taxNumber: "30/123/45678",
    },
    { "If-Match": ws.etag },
  );
  const settings = await apiCall(page, "GET", "/api/v1/invoice-settings");
  await apiCall(
    page,
    "PUT",
    "/api/v1/invoice-settings",
    {
      contactName: "Erika Test",
      contactPhone: "+49 30 1234567",
      contactEmail: "rechnung@malerbetrieb.example",
      iban: "DE02120300000000202051",
      bic: "BYLADEM1001",
      kleinunternehmer: false,
      numberPattern: "RE-{YYYY}-{NNNN}",
      creditNotePattern: "GS-{YYYY}-{NNNN}",
      defaultFormat: "ZUGFERD_EN16931",
      paymentTermsDays: 14,
    },
    { "If-Match": settings.etag },
  );
}

/** Waits for a mail to [email] whose subject contains [subject] and returns the token from its link. */
export async function mailToken(email: string, subject: string): Promise<string> {
  for (let i = 0; i < 60; i++) {
    const query = encodeURIComponent(`to:${email} subject:"${subject}"`);
    const { messages } = (await (await fetch(`${mailpit}/api/v1/search?query=${query}`)).json()) as {
      messages: { ID: string }[];
    };
    if (messages.length > 0) {
      const message = await (await fetch(`${mailpit}/api/v1/message/${messages[0]!.ID}`)).json();
      const token = /token=([A-Za-z0-9_-]+)/.exec(message.Text as string)?.[1];
      if (token) return token;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`No mail "${subject}" for ${email}`);
}

/**
 * Finishes a signed-up account through the API with the page's own cookies: email link,
 * password, 2FA enrolment. The browser ends up signed in.
 */
export async function verifyAndSignIn(page: Page, email: string, password: string): Promise<void> {
  const ip = `10.98.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;
  await page.request.get("/api/v1/auth/session", { headers: { "X-Forwarded-For": ip } });
  const post = async (path: string, data?: unknown) => {
    const cookie = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN");
    const response = await page.request.post(path, {
      data,
      headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? ""), "X-Forwarded-For": ip },
    });
    expect(response.ok(), `${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  };
  await post("/api/v1/auth/verify-email", { token: await mailToken(email, "bestätigen") });
  await post("/api/v1/auth/login", { email, password });
  const enrolment = await post("/api/v1/auth/totp/enrolment");
  await post("/api/v1/auth/totp/enrolment/confirm", { code: totp(enrolment.secret as string) });
}
