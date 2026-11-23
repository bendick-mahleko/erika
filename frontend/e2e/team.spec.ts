import { expect, test } from "@playwright/test";
import { apiCall, expectAccessible, mailToken, sampleInvoice, signedInAccount, verifyAndSignIn } from "./support";

/**
 * Stage 9 in the browser, against the full stack: the owner invites the tax advisor, who signs
 * up through the link, reads, comments and exports, but can't change anything (EX-01, EX-02);
 * the owner closes the account, which then is read-only (EX-04).
 */
test.describe("team, export and account closure (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(180_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

  test("an invited tax advisor signs up, comments and exports, but can't change anything", async ({
    page,
    browser,
  }) => {
    await signedInAccount(page, "e2e-mandant");
    // One received invoice (dated 2 Nov 2026 in the fixture).
    await page.goto("/inbox");
    await page
      .getByTestId("upload-input")
      .setInputFiles([{ name: "rechnung.xml", mimeType: "application/xml", buffer: sampleInvoice(`RE-${unique()}`) }]);
    await expect(page.getByTestId("document-list").getByText("Gültige E-Rechnung")).toBeVisible({ timeout: 90_000 });

    // The owner invites the tax advisor.
    const advisorEmail = `kanzlei-${unique()}@example.test`;
    await page.goto("/settings/team");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Team und Steuerberatung");
    await page.getByLabel("E-Mail-Adresse").fill(advisorEmail);
    await page.getByLabel("Rolle", { exact: true }).selectOption("ADVISOR");
    await page.getByRole("button", { name: "Einladung senden" }).click();
    await expect(page.getByTestId("invitation-list")).toContainText(advisorEmail);
    await expect(page.getByTestId("invitation-list")).toContainText("offen");
    await expectAccessible(page);

    // The advisor follows the link in a browser of their own and signs up.
    const token = await mailToken(advisorEmail, "lädt Sie zu ERIKA ein");
    const context = await browser.newContext();
    const advisor = await context.newPage();
    await advisor.goto(`/invitations/accept?token=${token}`);
    await expect(advisor.getByTestId("invitation")).toContainText("als Steuerberater/in");
    await expectAccessible(advisor);
    await advisor.getByRole("link", { name: "Konto anlegen und beitreten" }).click();
    await expect(advisor.getByTestId("joining")).toBeVisible();
    await expect(advisor.getByLabel("Name Ihres Unternehmens")).toHaveCount(0);
    await expect(advisor.getByLabel("E-Mail-Adresse", { exact: true })).toHaveValue(advisorEmail);
    const password = "ein langer Satz als Passwort";
    await advisor.getByLabel("Ihr Name").fill("Steuerkanzlei Test");
    await advisor.getByLabel("Passwort", { exact: true }).fill(password);
    await advisor.getByRole("checkbox").check();
    await advisor.getByRole("button", { name: "Konto anlegen" }).click();
    await expect(advisor.getByText("Bitte prüfen Sie Ihr Postfach")).toBeVisible();
    await verifyAndSignIn(advisor, advisorEmail, password);

    // Reading and commenting: yes. Changing the status: no.
    await advisor.goto("/inbox");
    await advisor.getByTestId("document-list").getByRole("link").first().click();
    await expect(advisor.getByRole("heading", { name: "Kommentare" })).toBeVisible();
    await advisor.getByLabel("Neuer Kommentar").fill("Bitte Leistungszeitraum ergänzen lassen.");
    await advisor.getByRole("button", { name: "Kommentar speichern" }).click();
    await expect(advisor.getByTestId("comments")).toContainText("Steuerkanzlei Test");
    await expectAccessible(advisor);
    const documentId = advisor.url().split("/").pop()!;
    const cookie = (await context.cookies()).find((c) => c.name === "XSRF-TOKEN");
    const change = await advisor.request.patch(`/api/v1/documents/${documentId}`, {
      data: { status: "CHECKED" },
      headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? "") },
    });
    expect(change.status()).toBe(403);

    // Export a period with DATEV file and download it after sign-in (EX-01).
    await advisor.goto("/exports");
    await expect(advisor.getByTestId("full-export")).toHaveCount(0);
    await advisor.getByLabel("Von (Rechnungsdatum)").fill("2026-11-01");
    await advisor.getByLabel("Bis (Rechnungsdatum)").fill(new Date().toISOString().slice(0, 10));
    await advisor.getByRole("button", { name: "Export starten" }).click();
    const list = advisor.getByTestId("export-list");
    await expect(list.getByTestId("export-state").first()).toHaveText("bereit", { timeout: 90_000 });
    await expectAccessible(advisor);
    const download = advisor.waitForEvent("download");
    await list
      .getByRole("link", { name: /herunterladen/ })
      .first()
      .click();
    expect((await download).suggestedFilename()).toMatch(/^ERIKA-Export_.*\.zip$/);
    await context.close();

    // The owner sees the comment and the advisor as a member.
    await page.goto("/settings/team");
    await expect(page.getByTestId("member-list")).toContainText("Steuerberater/in");
    await page.goto(`/documents/${documentId}`);
    await expect(page.getByTestId("comments")).toContainText("Bitte Leistungszeitraum ergänzen lassen.");
  });

  test("a closed account is read-only (EX-04)", async ({ page }) => {
    await signedInAccount(page, "e2e-closing");
    await page.goto("/settings/workspace");
    await page.getByLabel(/Zur Bestätigung den Namen des Arbeitsbereichs eingeben/).fill("Test Malerbetrieb");
    await expectAccessible(page);
    await page.getByRole("button", { name: "Konto endgültig schließen" }).click();
    await expect(page.getByTestId("closed-info")).toBeVisible();
    await expect(page.getByTestId("closed-banner")).toBeVisible();
    const cookie = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN");
    const write = await page.request.post("/api/v1/customers", {
      data: { name: "X", countryCode: "DE", vatTreatment: "DOMESTIC" },
      headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? ""), "Idempotency-Key": unique() },
    });
    expect(write.status()).toBe(403);
    // Taking the data out still works.
    await apiCall(page, "POST", "/api/v1/exports", { kind: "FULL" }, { "Idempotency-Key": unique() });
  });
});
