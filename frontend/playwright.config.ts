import { defineConfig, devices } from "@playwright/test";

/**
 * E2E tests run against a running stack. By default they start `next start`
 * (after `pnpm build`); in CI the whole Docker Compose stack is started instead
 * and E2E_BASE_URL points at it.
 */
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/**
 * Sign-in steps are rate-limited per client IP. Each worker process simulates its own client
 * (X-Forwarded-For, as the back-end tests do), so parallel workers don't lock each other out.
 * In production Caddy sets this header itself and ignores the client's.
 */
const simulatedIp = `10.97.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: { baseURL, trace: "retain-on-failure", extraHTTPHeaders: { "X-Forwarded-For": simulatedIp } },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    // NF-14: usable down to 375 px.
    { name: "mobile", use: { ...devices["Desktop Chrome"], viewport: { width: 375, height: 740 } } },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: "pnpm start", url: baseURL, reuseExistingServer: !process.env.CI, timeout: 60_000 },
});
