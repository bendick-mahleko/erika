import { expect, test, type Page } from "@playwright/test";
import { expectAccessible, mailToken, sampleInvoice, signedInAccount } from "./support";

/**
 * Stage 11 in the browser: password reset by email (ASVS 2.5), a keyboard-only pass through the
 * main flow (NF-11), and the Content Security Policy on every page (ASVS 14.4.3).
 */
test.describe("hardening (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(150_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  test("a forgotten password is reset by email; the second factor still applies", async ({ page, browser }) => {
    const { email } = await signedInAccount(page, "e2e-reset");
    const ip = `10.96.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;
    const context = await browser.newContext({ extraHTTPHeaders: { "X-Forwarded-For": ip } });
    const visitor = await context.newPage();
    await visitor.goto("/login");
    await visitor.getByRole("link", { name: "Passwort vergessen?" }).click();
    await expect(visitor.getByRole("heading", { level: 1 })).toHaveText("Passwort vergessen");
    await visitor.getByLabel("E-Mail-Adresse").fill(email);
    await expectAccessible(visitor);
    await visitor.getByRole("button", { name: "Link senden" }).click();
    await expect(visitor.getByText("ist eine E-Mail unterwegs")).toBeVisible();

    await visitor.goto(`/reset-password?token=${await mailToken(email, "zurücksetzen")}`);
    await expect(visitor.getByRole("heading", { level: 1 })).toHaveText("Neues Passwort festlegen");
    await visitor.getByLabel("Neues Passwort", { exact: true }).fill("ein ganz neuer langer Satz als Passwort");
    await visitor.getByRole("button", { name: "Passwort speichern" }).click();
    await expect(visitor.getByText("Ihr Passwort wurde geändert")).toBeVisible();

    await visitor.goto("/login");
    await expect(visitor.getByRole("heading", { level: 1 })).toHaveText("Anmelden");
    await visitor.getByLabel("E-Mail-Adresse").fill(email);
    await visitor.getByLabel("Passwort", { exact: true }).fill("ein ganz neuer langer Satz als Passwort");
    await visitor.getByRole("button", { name: "Weiter" }).click();
    await expect(visitor).toHaveURL(/\/login\/(totp|setup)$/);
    // The old session in the other browser has ended.
    await page.goto("/settings/account");
    await expect(page).toHaveURL(/\/login$/);
    await context.close();
  });

  test("the main flow works with the keyboard alone", async ({ page }) => {
    await signedInAccount(page, "e2e-keyboard");
    await page.goto("/inbox");
    await page
      .getByTestId("upload-input")
      .setInputFiles([{ name: "rechnung.xml", mimeType: "application/xml", buffer: sampleInvoice(`RE-${unique()}`) }]);
    await expect(page.getByTestId("document-list").getByText("Gültige E-Rechnung")).toBeVisible({ timeout: 90_000 });

    await page.goto("/inbox");
    // First stop: the skip link, which moves on to the content.
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Zum Inhalt springen" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/#main$/);

    await tabUntil(page, (el) => el.tagName === "A" && (el.getAttribute("href") ?? "").startsWith("/documents/"));
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/documents\//);
    await expect(page.getByRole("heading", { name: "Kommentare" })).toBeVisible();

    await tabUntil(page, (el) => el.id === "comment");
    await page.keyboard.type("Per Tastatur geprüft.");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Kommentar speichern" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("comments")).toContainText("Per Tastatur geprüft.");
  });

  test("every page sends a Content Security Policy with a per-request nonce", async ({ page }) => {
    const first = await page.goto("/");
    const second = await page.request.get("/login");
    const csp = first?.headers()["content-security-policy"] ?? "";
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    const nonce = (h: string) => /'nonce-([^']+)'/.exec(h)?.[1];
    expect(nonce(second.headers()["content-security-policy"] ?? "")).not.toEqual(nonce(csp));
    // No CSP violations while the page runs (the app's own scripts carry the nonce).
    const violations: string[] = [];
    page.on("console", (m) => {
      if (m.text().includes("Content Security Policy")) violations.push(m.text());
    });
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Weiter" })).toBeVisible();
    expect(violations).toEqual([]);
  });
});

/** Presses Tab until the focused element matches (at most 80 times). */
async function tabUntil(page: Page, match: (el: Element) => boolean) {
  const source = match.toString();
  for (let i = 0; i < 80; i++) {
    await page.keyboard.press("Tab");
    const hit = await page.evaluate((fn) => {
      const el = document.activeElement;
      return el ? (new Function(`return (${fn})`)() as (e: Element) => boolean)(el) : false;
    }, source);
    if (hit) return;
  }
  throw new Error("element not reachable with Tab");
}
