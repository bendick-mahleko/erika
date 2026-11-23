import { expect, test } from "@playwright/test";
import { completeSeller, expectAccessible, mailpit, signedInAccount } from "./support";

/**
 * Stage 8 exit criteria in the browser, against the full stack (API and worker):
 * customer and article lists (CR-05), the guided form that can't be submitted while
 * mandatory fields are missing (CR-01), issuing (CR-02, CR-03) and sending (CR-07).
 */
test.describe("invoice creator (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(180_000);

  test("create from saved customer and article → issue → send, in under 3 minutes (CR-05)", async ({ page }) => {
    await signedInAccount(page, "e2e-creator");
    await completeSeller(page);
    const recipient = `kunde-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`;

    // Master data, entered once through the UI.
    await page.goto("/customers");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Kunden");
    await page.getByTestId("new-customer").click();
    await page.getByLabel("Name / Firma").fill("Kunde AG");
    await page.getByLabel("Kundennummer").fill("K-1001");
    await page.getByLabel("Straße und Hausnummer").fill("Kundenweg 2");
    await page.getByLabel("PLZ", { exact: true }).fill("80331");
    await page.getByLabel("Ort", { exact: true }).fill("München");
    await page.getByLabel("E-Mail für Rechnungen").fill(recipient);
    await expectAccessible(page);
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByTestId("customer-list")).toContainText("Kunde AG");

    await page.goto("/articles");
    await page.getByTestId("new-article").click();
    await page.getByLabel("Bezeichnung", { exact: true }).fill("Malerarbeiten");
    await page.getByLabel("Einheit", { exact: true }).selectOption("HUR");
    await page.getByLabel("Preis netto (€)").fill("55,00");
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByTestId("article-list")).toContainText("Malerarbeiten");

    // CR-05: from saved data to an issued invoice.
    const started = Date.now();
    await page.goto("/invoices");
    await page.getByTestId("new-invoice").click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Rechnung – Entwurf");

    // CR-01: nothing chosen yet → "issue" is not possible and the missing fields are listed.
    await expect(page.getByTestId("issue")).toBeDisabled();
    await expect(page.getByTestId("problems")).toContainText("Kunde: Name");
    await expectAccessible(page);

    await page.getByLabel("Gespeicherten Kunden wählen").selectOption({ label: "Kunde AG (K-1001)" });
    await page.getByLabel("Artikel übernehmen").selectOption({ label: "Malerarbeiten" });
    await page.getByLabel("Menge", { exact: true }).fill("8");
    await page.getByLabel("Leistungs- oder Lieferdatum").fill(new Date().toISOString().slice(0, 10));
    await expect(page.getByTestId("save-state")).toHaveText("Gespeichert", { timeout: 15_000 });
    await expect(page.getByTestId("gross")).toHaveText(/523,60\s€/);
    await expect(page.getByTestId("issue")).toBeEnabled();
    await page.getByTestId("issue").click();

    await expect(page.getByTestId("issued-title")).toHaveText(/^Rechnung RE-\d{4}-\d{4}$/, { timeout: 60_000 });
    const elapsed = (Date.now() - started) / 1000;
    expect(elapsed, `from saved data to issued invoice took ${elapsed} s`).toBeLessThan(180);
    await expect(page.getByTestId("issued-status")).toHaveText("Ausgestellt");
    await expectAccessible(page);

    // CR-07: send by email; the delivery is logged and the email reaches the customer.
    await expect(page.getByLabel("An", { exact: true })).toHaveValue(recipient);
    await page.getByTestId("send").click();
    await expect(page.getByTestId("delivery-status")).toHaveText("Versendet", { timeout: 60_000 });
    await expect(page.getByTestId("issued-status")).toHaveText("Versendet");
    await expect(page.getByRole("link", { name: "Gesendete E-Mail (archiviert)" })).toBeVisible();

    let attachments = 0;
    for (let i = 0; i < 30 && attachments === 0; i++) {
      const search = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:${recipient}`)}`);
      const { messages } = (await search.json()) as { messages: { ID: string; Attachments: number }[] };
      attachments = messages[0]?.Attachments ?? 0;
      if (attachments === 0) await new Promise((r) => setTimeout(r, 1000));
    }
    expect(attachments, "the invoice file is attached").toBe(1);
  });
});
