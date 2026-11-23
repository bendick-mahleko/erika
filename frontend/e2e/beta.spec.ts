import { expect, test } from "@playwright/test";
import { expectAccessible, mailpit, signedInAccount } from "./support";

/** Stage 7: closed-beta essentials (ADR 0030) – legal pages, terms at sign-up, in-app feedback. */
test.describe("legal pages", () => {
  const pages = [
    { path: "/impressum", heading: "Impressum" },
    { path: "/datenschutz", heading: "Datenschutzhinweise" },
    { path: "/nutzungsbedingungen", heading: "Nutzungsbedingungen für die Beta von ERIKA" },
    { path: "/auftragsverarbeitung", heading: "Vertrag zur Auftragsverarbeitung (Art. 28 DSGVO)" },
    { path: "/unterauftragsverarbeiter", heading: "Unterauftragsverarbeiter" },
  ];

  for (const { path, heading } of pages) {
    test(`${path} is public, linked in the footer and accessible`, async ({ page }) => {
      await page.goto("/");
      await page.getByRole("navigation", { name: "Rechtliches und Service" }).getByRole("link").first().waitFor();
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(heading);
      await expectAccessible(page);
    });
  }

  test("the sub-processor list names the hosting provider (NF-01)", async ({ page }) => {
    await page.goto("/unterauftragsverarbeiter");
    await expect(page.getByRole("heading", { name: "Hetzner Online GmbH" })).toBeVisible();
  });
});

test.describe("sign-up terms", () => {
  test("the terms checkbox is required and links the documents", async ({ page }) => {
    await page.goto("/signup");
    const checkbox = page.getByRole("checkbox", { name: /Beta-Nutzungsbedingungen/ });
    await expect(checkbox).toHaveAttribute("required", "");
    await expect(page.getByRole("link", { name: "Vertrags zur Auftragsverarbeitung" })).toHaveAttribute(
      "href",
      "/auftragsverarbeitung",
    );
  });
});

test.describe("feedback (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");

  test("a signed-in user sends feedback from any page; the team gets an email", async ({ page }) => {
    test.setTimeout(90_000);
    const { email } = await signedInAccount(page, "feedback");
    await page.goto("/inbox");

    await page.getByTestId("feedback-button").click();
    const dialog = page.getByRole("dialog", { name: "Feedback an das ERIKA-Team" });
    await expect(dialog).toBeVisible();
    await expectAccessible(page);
    const message = `Die Suche ist super – ${Date.now()}`;
    await dialog.getByLabel("Lob").check();
    await dialog.getByLabel("Ihre Nachricht").fill(message);
    await dialog.getByRole("button", { name: "Senden" }).click();
    await expect(dialog.getByTestId("feedback-thanks")).toBeVisible();
    await dialog.getByRole("button", { name: "Schließen" }).click();
    await expect(dialog).toBeHidden();

    // Local stack: ERIKA_FEEDBACK_TO=beta@erika.local (infra/local/compose.yml).
    let found = false;
    for (let i = 0; i < 60 && !found; i++) {
      const search = await fetch(
        `${mailpit}/api/v1/search?query=${encodeURIComponent(`to:beta@erika.local "${message}"`)}`,
      );
      const { messages } = (await search.json()) as { messages: { Snippet: string }[] };
      found = messages.length > 0;
      if (!found) await new Promise((r) => setTimeout(r, 1000));
    }
    expect(found, `feedback mail from ${email} reached the team`).toBeTruthy();
  });
});
