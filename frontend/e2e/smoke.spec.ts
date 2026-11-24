import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test.describe("landing page and system status", () => {
  test("home page renders in German and is accessible", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveTitle("ERIKA");
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "E-Rechnungen empfangen, verstehen und sicher aufbewahren",
    );

    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
    expect(results.violations).toEqual([]);
  });

  test("the system status page shows the status it got from the API", async ({ page }) => {
    // Only meaningful when the API is running (CI full-stack job sets E2E_EXPECT_API=true).
    test.skip(process.env.E2E_EXPECT_API !== "true", "API not part of this run");
    await page.goto("/systemstatus");
    const components = page.getByTestId("health-components");
    await expect(components).toBeVisible();
    await expect(components.getByText("Datenbank")).toBeVisible();
    await expect(page.getByText("Betriebsbereit").first()).toBeVisible();
  });
});
