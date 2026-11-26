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

export type Member = components["schemas"]["Member"];
export type Role = components["schemas"]["Role"];
export type Invitation = components["schemas"]["Invitation"];
export type InvitationPreview = components["schemas"]["InvitationPreview"];
export type Export = components["schemas"]["Export"];
export type ExportScope = components["schemas"]["ExportScope"];
export type DatevSettings = components["schemas"]["DatevSettings"];
export type DatevSettingsUpdate = components["schemas"]["DatevSettingsUpdate"];
export type Comment = components["schemas"]["Comment"];

export type BillingStatus = components["schemas"]["BillingStatus"];
export type BillingPlan = components["schemas"]["BillingPlan"];

export type IntakeSettings = components["schemas"]["IntakeSettings"];
export type IntakeSender = components["schemas"]["IntakeSender"];
export type QuarantinedMail = components["schemas"]["QuarantinedMail"];
export type RecurringInvoice = components["schemas"]["RecurringInvoice"];
export type RecurringInterval = components["schemas"]["RecurringInterval"];

export type CustomerType = components["schemas"]["CustomerType"];
export type Site = components["schemas"]["Site"];
export type SiteInput = components["schemas"]["SiteInput"];
export type StaffLevel = components["schemas"]["StaffLevel"];
export type StaffMember = components["schemas"]["StaffMember"];
export type Device = components["schemas"]["Device"];
export type ChecklistTemplate = components["schemas"]["ChecklistTemplate"];
export type ChecklistItem = components["schemas"]["ChecklistItem"];
export type ChecklistItemKind = components["schemas"]["ChecklistItemKind"];
export type WorkOrder = components["schemas"]["WorkOrder"];
export type WorkOrderInput = components["schemas"]["WorkOrderInput"];
export type WorkOrderStatus = components["schemas"]["WorkOrderStatus"];
// MARA Stage 8: invoicing from reports, payment reminders.
export type BillableReport = components["schemas"]["BillableReport"];
export type ReportInvoiceLine = components["schemas"]["ReportInvoiceLine"];
export type ReportInvoicePreview = components["schemas"]["ReportInvoicePreview"];
export type ReportInvoicingSettings = components["schemas"]["ReportInvoicingSettings"];
export type TravelMode = components["schemas"]["TravelMode"];
export type OverdueInvoice = components["schemas"]["OverdueInvoice"];
