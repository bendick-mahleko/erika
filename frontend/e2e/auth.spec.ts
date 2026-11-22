import { createHmac } from "node:crypto";
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];
const mailpit = process.env.E2E_MAILPIT_URL ?? "http://localhost:8025";

async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(WCAG).analyze();
  expect(results.violations).toEqual([]);
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), as an authenticator app computes it. */
function totp(base32Secret: string, offsetSteps = 0): string {
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
async function verificationToken(email: string): Promise<string> {
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

test.describe("sign-in pages", () => {
  test("sign-up and sign-in pages are German and accessible", async ({ page }) => {
    await page.goto("/signup");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Konto anlegen");
    await expect(page.getByLabel("Passwort")).toHaveAttribute("autocomplete", "new-password");
    await expectAccessible(page);

    await page.goto("/login");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Anmelden");
    await expectAccessible(page);
  });

  test("protected pages send anonymous visitors to sign-in", async ({ page }) => {
    await page.goto("/settings/workspace");
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("account flow (full stack)", () => {
  // Needs API, worker and Mailpit (CI full-stack job sets E2E_EXPECT_API=true).
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  // One account per browser project; the flow is sequential.
  test.describe.configure({ mode: "serial" });

  test("sign up, confirm email, set up 2FA, edit workspace, sign out, sign in again", async ({ page }, testInfo) => {
    // Waits for the worker to deliver the verification mail.
    test.setTimeout(90_000);
    const email = `e2e-${testInfo.project.name}-${Date.now()}@example.test`;
    const password = "ein langer Satz als Passwort";

    await page.goto("/signup");
    await page.getByLabel("Ihr Name").fill("Erika Test");
    await page.getByLabel("Name Ihres Unternehmens").fill("Test Malerbetrieb");
    await page.getByLabel("E-Mail-Adresse").fill(email);
    await page.getByLabel("Passwort").fill(password);
    await page.getByRole("button", { name: "Konto anlegen" }).click();
    await expect(page.getByText("Bitte prüfen Sie Ihr Postfach")).toBeVisible();

    await page.goto(`/verify-email?token=${await verificationToken(email)}`);
    await expect(page.getByText("Ihre E-Mail-Adresse ist bestätigt")).toBeVisible();

    await page.goto("/login");
    await page.getByLabel("E-Mail-Adresse").fill(email);
    await page.getByLabel("Passwort").fill(password);
    await page.getByRole("button", { name: "Weiter" }).click();

    await expect(page).toHaveURL(/\/login\/setup$/);
    await expect(page.getByRole("img", { name: "QR-Code für Ihre Authenticator-App" })).toBeVisible();
    await expectAccessible(page);
    const secret = (await page.getByTestId("totp-secret").textContent())!.trim();
    await page.getByLabel("Code aus der App").fill(totp(secret));
    await page.getByRole("button", { name: "Einrichtung abschließen" }).click();
    await expect(page.getByTestId("recovery-codes").locator("li")).toHaveCount(10);
    await page.getByRole("button", { name: /Codes gesichert/ }).click();

    await expect(page).toHaveURL(/\/settings\/workspace$/);
    await expect(page.getByText("Angemeldet als Erika Test")).toBeVisible();
    await expectAccessible(page);
    await page.getByLabel("Name des Unternehmens").fill("Test Malerbetrieb GmbH");
    await page.getByLabel("USt-IdNr.").fill("DE123456789");
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByText("Gespeichert.")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Name des Unternehmens")).toHaveValue("Test Malerbetrieb GmbH");

    await page.getByRole("button", { name: "Abmelden" }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto("/settings/workspace");
    await expect(page).toHaveURL(/\/login$/);

    // Second sign-in: TOTP (the next time step, since each code works once).
    await page.getByLabel("E-Mail-Adresse").fill(email);
    await page.getByLabel("Passwort").fill(password);
    await page.getByRole("button", { name: "Weiter" }).click();
    await expect(page).toHaveURL(/\/login\/totp$/);
    await page.getByLabel("Code aus der App").fill(totp(secret, 1));
    await page.getByRole("button", { name: "Anmelden" }).click();
    await expect(page).toHaveURL(/\/settings\/workspace$/);
  });
});
