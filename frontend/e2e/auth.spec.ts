import { expect, test } from "@playwright/test";
import { expectAccessible, totp, verificationToken } from "./support";

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
