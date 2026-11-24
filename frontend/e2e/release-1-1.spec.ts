import { expect, test, type Page } from "@playwright/test";
import { apiCall, completeSeller, expectAccessible, sampleInvoice, sendMail, signedInAccount } from "./support";

/**
 * Release 1.1 in the browser: English UI and viewer (VW-07, NF-13), GiroCode (AR-06), sender
 * allow-list and quarantine (IN-06), logo and accent colour (CR-08), recurring invoices (CR-09).
 */
test.describe("release 1.1 (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(180_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const smtp = () => [process.env.E2E_SMTP_HOST ?? "localhost", Number(process.env.E2E_SMTP_PORT ?? 2525)] as const;

  async function uploadAndOpen(page: Page, invoiceNumber: string) {
    await page.goto("/inbox");
    await page
      .getByTestId("upload-input")
      .setInputFiles([{ name: "rechnung.xml", mimeType: "application/xml", buffer: sampleInvoice(invoiceNumber) }]);
    await expect(page.getByTestId("upload-results")).toContainText("rechnung.xml", { timeout: 60_000 });
    const list = page.getByTestId("document-list");
    await expect(list.getByText(/Gültige E-Rechnung|Valid e-invoice/)).toBeVisible({ timeout: 60_000 });
    await list.getByRole("link", { name: "Farbwerk Beispiel" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toContainText(invoiceNumber);
  }

  test("the language switch shows the app and the invoice in English, with a GiroCode", async ({ page }) => {
    await signedInAccount(page, "e2e-english");
    const number = `EN-${unique()}`;
    await uploadAndOpen(page, number);

    // AR-06: transfer details and the QR code, in German first.
    await expect(page.getByRole("heading", { level: 2, name: "Überweisung" })).toBeVisible();
    await expect(page.getByTestId("girocode")).toBeVisible();
    await expect(page.getByTestId("transfer-iban")).toHaveText("DE02 1203 0000 0000 2020 51");
    await expect(page.getByTestId("transfer-reference")).toHaveText(number);
    await expect(page.getByRole("button", { name: "IBAN kopieren" })).toBeVisible();

    // NF-13 / VW-07: English labels and formats after switching.
    await page.getByRole("button", { name: "English" }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Bank transfer" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await expect(page.getByText("Seller name", { exact: true })).toBeVisible();
    await expect(page.locator('[data-bt="BT-2"]')).toHaveText("2026-11-02");
    await expect(page.locator('[data-bt="BT-112"]')).toHaveText("1,785.00 EUR");
    await expectAccessible(page);

    // The choice sticks for the next page, then back to German.
    await page.goto("/inbox");
    await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
    await page.getByRole("button", { name: "Deutsch" }).click();
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
  });

  test("with the sender list on, unknown senders wait in quarantine until released", async ({ page }) => {
    await signedInAccount(page, "e2e-allowlist");
    await page.goto("/settings/intake");
    await expect(page.getByRole("heading", { level: 1, name: "E-Mail-Eingang" })).toBeVisible();
    await page.getByLabel("Nur E-Mails von Absendern auf der Liste direkt übernehmen").check();
    await expect(page.getByRole("status").filter({ hasText: "Gespeichert." })).toBeVisible();
    await page.getByLabel("Absender hinzufügen").fill("@bekannt.example");
    await page.getByRole("button", { name: "Hinzufügen" }).click();
    await expect(page.getByTestId("sender-list")).toContainText("@bekannt.example");
    await expectAccessible(page);

    // sendMail sends from rechnung@lieferant.example: not on the list.
    const address = (await page.getByTestId("intake-address").textContent())!.trim();
    const [host, port] = smtp();
    await sendMail(host, port, address, [
      { name: "quarantaene.xml", type: "application/xml", data: sampleInvoice(`Q-${unique()}`) },
    ]);
    const quarantine = page.getByTestId("quarantine-list");
    await expect(async () => {
      await page.reload();
      await expect(quarantine).toContainText("rechnung@lieferant.example", { timeout: 2_000 });
    }).toPass({ timeout: 120_000, intervals: [2_000] });

    await quarantine.getByRole("button", { name: "Übernehmen und Absender erlauben" }).click();
    await expect(page.getByTestId("quarantine-empty")).toBeVisible();
    await expect(page.getByTestId("sender-list")).toContainText("rechnung@lieferant.example");
    await page.goto("/inbox");
    const list = page.getByTestId("document-list");
    await expect(async () => {
      await page.reload();
      await expect(list.getByText("Gültige E-Rechnung")).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 120_000, intervals: [2_000] });
  });

  test("logo, accent colour and a recurring invoice", async ({ page }) => {
    await signedInAccount(page, "e2e-recurring");
    await completeSeller(page);

    // CR-08: logo upload with preview, accent colour saved with the settings.
    await page.goto("/settings/invoices");
    await page.getByLabel("Logo-Datei").setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: PNG });
    await page.getByRole("button", { name: "Logo hochladen" }).click();
    await expect(page.getByTestId("logo-preview")).toBeVisible();
    await page.getByLabel("Akzentfarbe für Titel und Tabellenkopf verwenden").check();
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByText("Gespeichert.", { exact: true })).toBeVisible();
    expect((await apiCall(page, "GET", "/api/v1/invoice-settings")).body.accentColor).toBe("#1A5FB4");
    await expectAccessible(page);

    // CR-09: from a draft to a recurring invoice; the list shows it and it can be paused.
    const draft = await apiCall(page, "POST", "/api/v1/invoices", CONTENT, { "Idempotency-Key": crypto.randomUUID() });
    await page.goto(`/invoices/${draft.body.id}`);
    await page.getByText("Als wiederkehrende Rechnung anlegen").click();
    await page.getByLabel("Name der wiederkehrenden Rechnung").fill("Wartungsvertrag");
    await page.getByLabel("Rhythmus").selectOption("QUARTERLY");
    await page.getByRole("button", { name: "Anlegen" }).click();
    await expect(page.getByText("Die wiederkehrende Rechnung wurde angelegt.")).toBeVisible();
    await page.getByRole("link", { name: "Wiederkehrende Rechnungen" }).click();
    const table = page.getByTestId("recurring-list");
    await expect(table).toContainText("Wartungsvertrag");
    await expect(table).toContainText("Vierteljährlich");
    await page.getByRole("button", { name: "Wartungsvertrag pausieren" }).click();
    await expect(table).toContainText("Pausiert");
    await expectAccessible(page);
  });
});

/** A 1×1 PNG. */
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64",
);

const CONTENT = {
  format: "ZUGFERD_EN16931",
  vatTreatment: "DOMESTIC",
  buyer: {
    name: "Kunde AG",
    street: "Kundenweg 2",
    postalCode: "80331",
    city: "München",
    countryCode: "DE",
    email: "eingang@kunde.example",
  },
  lines: [{ name: "Wartung", quantity: "1", unitCode: "C62", netPrice: "120.00", vatRate: "STANDARD_19" }],
};
