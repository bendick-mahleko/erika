import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

/**
 * Typed client for calls from the browser (client components). Same origin: /api/v1 is
 * forwarded to the back end (src/proxy.ts locally, Caddy in staging/production), so the
 * session cookie stays first-party.
 *
 * State-changing requests carry the CSRF token: the back end sets the `XSRF-TOKEN`
 * cookie, we echo it in the `X-XSRF-TOKEN` header.
 */
export type Problem = components["schemas"]["Problem"];
export type Session = components["schemas"]["Session"];
export type Workspace = components["schemas"]["Workspace"];
export type WorkspaceUpdate = components["schemas"]["WorkspaceUpdate"];
export type Upload = components["schemas"]["Upload"];
export type IntakeItem = components["schemas"]["IntakeItem"];
export type DocumentSummary = components["schemas"]["DocumentSummary"];
export type Document = components["schemas"]["Document"];
export type DocumentStatus = components["schemas"]["DocumentStatus"];
export type DocumentFilter = Omit<NonNullable<paths["/documents"]["get"]["parameters"]["query"]>, "limit" | "cursor">;

function readCookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

const csrf: Middleware = {
  async onRequest({ request }) {
    if (request.method === "GET") return request;
    let token = readCookie("XSRF-TOKEN");
    if (!token) {
      // GET /auth/session sets the cookie.
      await fetch("/api/v1/auth/session", { credentials: "same-origin" });
      token = readCookie("XSRF-TOKEN");
    }
    if (token) request.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
    return request;
  },
};

export const browserApi = createClient<paths>({ baseUrl: "/api/v1", credentials: "same-origin" });
browserApi.use(csrf);

/** Stable error code from a problem response, or "network" if the request didn't get through. */
export function problemCode(error: unknown): string {
  if (error && typeof error === "object" && "code" in error && typeof error.code === "string") return error.code;
  return "generic";
}

export type Invoice = components["schemas"]["Invoice"];
export type InvoiceContent = components["schemas"]["InvoiceContent"];
export type InvoiceLineInput = components["schemas"]["InvoiceLineInput"];
export type InvoiceFormat = components["schemas"]["InvoiceFormat"];
export type InvoiceSettings = components["schemas"]["InvoiceSettings"];
export type DraftProblem = components["schemas"]["DraftProblem"];
export type VatTreatment = components["schemas"]["VatTreatment"];
export type VatRate = components["schemas"]["VatRate"];
export type Customer = components["schemas"]["Customer"];
export type CustomerInput = components["schemas"]["CustomerInput"];
export type Article = components["schemas"]["Article"];
export type ArticleInput = components["schemas"]["ArticleInput"];

/** A fresh Idempotency-Key for POST requests that create or send something (Software Design §6). */
export function idempotencyKey(): string {
  return crypto.randomUUID();
}
