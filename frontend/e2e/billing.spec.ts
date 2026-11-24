import { expect, test } from "@playwright/test";
import { expectAccessible, signedInAccount } from "./support";

/**
 * Stage 10 in the browser. The local stack runs without Stripe (billing switched off, as in the
 * closed beta), so this checks the page and its accessibility; the payment flow is covered by
 * BillingIT and the Stripe test-clock run in docs/ops/billing.md.
 */
test.describe("billing page (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");

  test("the billing page explains the state and is accessible", async ({ page }) => {
    await signedInAccount(page, "e2e-billing");
    await page.goto("/settings/billing");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Abrechnung");
    await expect(page.getByRole("link", { name: "Abrechnung" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("billing-state")).toBeVisible();
    await expect(page.getByTestId("billing-banner")).toHaveCount(0);
    await expectAccessible(page);
  });
});
