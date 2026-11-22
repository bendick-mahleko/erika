import { expect, test, type Page } from "@playwright/test";
import { eicar, expectAccessible, plainPdf, sampleInvoice, sendMail, signedInAccount, zip } from "./support";

/**
 * Stage 5 exit criteria in the browser, against the full Docker stack (API, worker, ClamAV,
 * Postfix): upload, ZIP, duplicate notice, virus rejection, plain PDF label, and email intake.
 */
test.describe("intake (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  // Each test waits for the worker (and ClamAV); email delivery adds Postfix.
  test.setTimeout(150_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  async function upload(page: Page, files: { name: string; mimeType: string; buffer: Buffer }[]) {
    await page.getByTestId("upload-input").setInputFiles(files);
  }

  test("upload an XRechnung, see it validated, re-upload shows possible duplicate", async ({ page }) => {
    await signedInAccount(page, "e2e-upload");
    await page.goto("/inbox");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Eingang");
    await expect(page.getByTestId("intake-address")).toContainText("@in.erika.local");
    await expectAccessible(page);

    const invoice = { name: "rechnung.xml", mimeType: "application/xml", buffer: sampleInvoice(`RE-${unique()}`) };
    await upload(page, [invoice]);
    await expect(page.getByTestId("upload-results")).toContainText("rechnung.xml: übernommen", { timeout: 60_000 });
    // The list refreshes by itself while the document is being checked.
    const list = page.getByTestId("document-list");
    await expect(list.getByText("Gültige E-Rechnung")).toBeVisible({ timeout: 60_000 });
    await expect(list.getByText("Farbwerk Beispiel")).toBeVisible();

    await upload(page, [invoice]);
    await expect(page.getByTestId("upload-results")).toContainText(
      "Mögliches Duplikat – diese Datei ist bereits im Archiv",
      {
        timeout: 60_000,
      },
    );

    await page.reload();
    await expect(page.getByTestId("inbox-badge")).toHaveText("1");
    await list.getByRole("link", { name: "Farbwerk Beispiel" }).click();
    await expect(page.getByTestId("validation-level")).toHaveText("Gültige E-Rechnung");
    await expect(page.getByText("1.785,00 €").first()).toBeVisible();
    await expectAccessible(page);
  });

  test("a ZIP with 10 invoices gives 10 inbox items", async ({ page }) => {
    await signedInAccount(page, "e2e-zip");
    await page.goto("/inbox");
    const run = unique();
    const entries = Array.from({ length: 10 }, (_, i) => ({
      name: `re-${i + 1}.xml`,
      data: sampleInvoice(`ZIP-${run}-${i + 1}`),
    }));
    await upload(page, [{ name: "rechnungen.zip", mimeType: "application/zip", buffer: zip(entries) }]);
    await expect(page.getByTestId("upload-results").getByText(/: übernommen/)).toHaveCount(10, { timeout: 90_000 });
    await expect(page.getByTestId("document-list").locator("li")).toHaveCount(10);
  });

  test("a virus is rejected, a plain PDF is labelled 'not an e-invoice'", async ({ page }) => {
    await signedInAccount(page, "e2e-reject");
    await page.goto("/inbox");
    await upload(page, [
      { name: "eicar.xml", mimeType: "application/xml", buffer: eicar() },
      { name: "scan.pdf", mimeType: "application/pdf", buffer: plainPdf(`Scan ${unique()}`) },
    ]);
    const results = page.getByTestId("upload-results");
    await expect(results).toContainText("eicar.xml: nicht übernommen – In der Datei wurde Schadsoftware gefunden", {
      timeout: 60_000,
    });
    await expect(results).toContainText("scan.pdf: übernommen", { timeout: 60_000 });

    // The list refreshes by itself while the document is being checked.
    const list = page.getByTestId("document-list");
    await expect(list.getByText("Keine E-Rechnung (sonstige Rechnung)")).toBeVisible({ timeout: 60_000 });
    await list.getByRole("link", { name: "scan.pdf" }).click();
    await expect(page.getByTestId("not-einvoice")).toContainText("Keine E-Rechnung (sonstige Rechnung)");
    await expect(page.getByTestId("not-einvoice")).toContainText("keine strukturierten Rechnungsdaten");
  });

  test("an email with an XRechnung attachment appears in the inbox within 2 minutes", async ({ page }) => {
    await signedInAccount(page, "e2e-mail");
    await page.goto("/inbox");
    const address = (await page.getByTestId("intake-address").textContent())!.trim();
    const started = Date.now();
    await sendMail(process.env.E2E_SMTP_HOST ?? "localhost", Number(process.env.E2E_SMTP_PORT ?? 2525), address, [
      { name: "rechnung-mail.xml", type: "application/xml", data: sampleInvoice(`MAIL-${unique()}`) },
    ]);
    const list = page.getByTestId("document-list");
    await expect(async () => {
      await page.reload();
      await expect(list.getByText("Gültige E-Rechnung")).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 120_000, intervals: [2_000] });
    expect(Date.now() - started).toBeLessThan(120_000);
    await expect(list).toContainText("E-Mail");
  });
});
