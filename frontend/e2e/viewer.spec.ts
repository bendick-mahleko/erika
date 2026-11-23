import { expect, test, type Page } from "@playwright/test";
import { expectAccessible, sampleInvoice, signedInAccount } from "./support";

/**
 * Stage 6 exit criteria in the browser: upload → view → change status → history shows user
 * and time; the viewer labels every field in German; downloads; search; axe on inbox and viewer.
 */
test.describe("viewer and inbox (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(150_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  async function uploadAndOpen(page: Page, invoiceNumber: string, xml?: Buffer) {
    await page.goto("/inbox");
    await page
      .getByTestId("upload-input")
      .setInputFiles([
        { name: "rechnung.xml", mimeType: "application/xml", buffer: xml ?? sampleInvoice(invoiceNumber) },
      ]);
    await expect(page.getByTestId("upload-results")).toContainText("rechnung.xml: übernommen", { timeout: 60_000 });
    const list = page.getByTestId("document-list");
    await expect(list.getByText(/Gültige E-Rechnung|Fehlerhaft/)).toBeVisible({ timeout: 60_000 });
    await list.getByRole("link", { name: "Farbwerk Beispiel" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toContainText(invoiceNumber);
  }

  test("upload, view with German labels, change status, history shows user and time", async ({ page }) => {
    await signedInAccount(page, "e2e-viewer");
    const number = `RE-${unique()}`;
    await uploadAndOpen(page, number);

    // VW-01: German labels and German formatting; VW-04: green panel.
    await expect(page.getByTestId("validation-level")).toHaveText("Gültige E-Rechnung");
    await expect(page.locator('[data-bt="BT-27"]')).toHaveText("Farbwerk Beispiel GmbH");
    await expect(page.getByText("Name des Verkäufers", { exact: true })).toBeVisible();
    await expect(page.locator('[data-bt="BT-112"]')).toHaveText("1.785,00 EUR");
    await expect(page.locator('[data-bt="BT-2"]')).toHaveText("02.11.2026");
    await expect(page.locator('[data-section="BG-25"] [data-section="BG-25"]')).toHaveCount(2);
    await expectAccessible(page);

    // Downloads: original unchanged with digest, PDF view copy.
    const original = await page.request.get(
      (await page.getByRole("link", { name: "Original herunterladen" }).getAttribute("href")) ?? "",
    );
    expect(original.status()).toBe(200);
    expect(original.headers()["repr-digest"]).toMatch(/^sha-256=:.+:$/);
    expect((await original.body()).toString("utf-8")).toContain(number);
    const pdf = await page.request.get(
      (await page.getByRole("link", { name: "PDF-Ansichtskopie herunterladen" }).getAttribute("href")) ?? "",
    );
    expect(pdf.status()).toBe(200);
    expect(pdf.headers()["content-type"]).toBe("application/pdf");
    expect(pdf.headers()["content-disposition"]).toMatch(/^attachment/);

    // AR-03: status change with a note; the history shows user, time and note.
    await expect(page.getByTestId("current-status")).toHaveText("Neu");
    await page.getByLabel("Neuer Status").selectOption("CHECKED");
    await page.getByLabel("Notiz zur Änderung (optional)").fill("Betrag stimmt");
    await page.getByRole("button", { name: "Status ändern" }).click();
    await expect(page.getByTestId("current-status")).toHaveText("Geprüft");
    const history = page.getByTestId("status-history");
    await expect(history).toContainText("Erika Test – Neu → Geprüft");
    await expect(history).toContainText("Betrag stimmt");
    await expect(history).toContainText(/\d{2}\.\d{2}\.\d{4}|\d{1,2}\. \w+/);

    await page.getByLabel("Schlagwörter").fill("Material, Treppenhaus");
    await page.getByRole("button", { name: "Schlagwörter und Notiz speichern" }).click();
    await expect(page.getByText("Gespeichert.")).toBeVisible();

    // The inbox shows the new status and finds the invoice by search.
    await page.goto(`/inbox?q=${encodeURIComponent(number)}`);
    const list = page.getByTestId("document-list");
    await expect(list.locator("li")).toHaveCount(1);
    await expect(list.getByTestId("status-badge")).toHaveText("Geprüft");
    await expectAccessible(page);
    await page.goto("/inbox?q=Farbwerkk"); // typo: trigram similarity
    await expect(page.getByTestId("document-list").locator("li")).toHaveCount(1);
    await page.goto("/inbox?status=PAID");
    await expect(page.getByText("Keine Dokumente passen zu diesen Filtern.")).toBeVisible();
  });

  test("bulk status change in the inbox", async ({ page }) => {
    await signedInAccount(page, "e2e-bulk");
    await page.goto("/inbox");
    const run = unique();
    await page.getByTestId("upload-input").setInputFiles([
      { name: "a.xml", mimeType: "application/xml", buffer: sampleInvoice(`A-${run}`) },
      { name: "b.xml", mimeType: "application/xml", buffer: sampleInvoice(`B-${run}`) },
    ]);
    await expect(page.getByTestId("upload-results").getByText(/: übernommen/)).toHaveCount(2, { timeout: 60_000 });
    const list = page.getByTestId("document-list");
    await expect(list.locator("li")).toHaveCount(2);
    for (const box of await list.getByRole("checkbox").all()) await box.check();
    await page.getByLabel("Status setzen auf").selectOption("APPROVED");
    await page.getByRole("button", { name: "Für Auswahl übernehmen" }).click();
    await expect(page.getByTestId("bulk-bar")).toContainText("2 Dokumente auf „Freigegeben“ gesetzt.");
    await expect(list.getByTestId("status-badge").filter({ hasText: "Freigegeben" })).toHaveCount(2);
  });

  test("an invoice with errors offers a prepared correction request", async ({ page }) => {
    await signedInAccount(page, "e2e-correction");
    const number = `ERR-${unique()}`;
    // Without the buyer reference (BR-DE-15) the XRechnung is invalid.
    const broken = Buffer.from(
      sampleInvoice(number)
        .toString("utf-8")
        .replace(/<cbc:BuyerReference>[^<]*<\/cbc:BuyerReference>/, ""),
    );
    await uploadAndOpen(page, number, broken);
    await expect(page.getByTestId("validation-level")).toHaveText("Fehlerhaft");
    await expect(page.getByText("BR-DE-15")).toBeVisible();
    await page.getByRole("button", { name: "Korrigierte Rechnung anfordern" }).click();
    const text = page.getByLabel("Vorbereiteter E-Mail-Text");
    await expect(text).toHaveValue(new RegExp(`Bitte um korrigierte Rechnung ${number}`));
    await expect(text).toHaveValue(/Käuferreferenz/);
    await expect(page.getByRole("link", { name: "Im E-Mail-Programm öffnen" })).toHaveAttribute(
      "href",
      /^mailto:max@farbwerk-beispiel\.de\?subject=/,
    );
    await expectAccessible(page);
  });
});
