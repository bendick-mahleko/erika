import "server-only";
import { cache } from "react";
import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

/**
 * Typed client for the ERIKA API, generated from api/openapi.yaml (`pnpm gen:api`).
 *
 * Server-side only: server components call the API over the internal network
 * (ERIKA_API_INTERNAL_URL). Browser calls will go through the same origin via
 * the reverse proxy once there are interactive features.
 */
const baseUrl = `${process.env.ERIKA_API_INTERNAL_URL ?? "http://localhost:8080"}/api/v1`;

export const api = createClient<paths>({ baseUrl });

export type Health = components["schemas"]["Health"];
export type HealthStatus = components["schemas"]["HealthStatus"];

/** Fetches /health. Returns null if the API can't be reached at all. */
export async function fetchHealth(): Promise<Health | null> {
  try {
    const { data, error, response } = await api.GET("/health", {
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    // 503 still carries a Health body describing what is down.
    if (data) return data;
    if (response.status === 503 && error) return error as unknown as Health;
    return null;
  } catch {
    return null;
  }
}

export type Session = components["schemas"]["Session"];
export type Workspace = components["schemas"]["Workspace"];

/** The browser's cookies, forwarded so the API sees the user's session (server components only). */
async function sessionHeaders(): Promise<HeadersInit> {
  const { cookies } = await import("next/headers");
  const cookie = (await cookies()).toString();
  return cookie ? { cookie } : {};
}

/**
 * Sign-in state of the current browser session; ANONYMOUS if the API can't be reached.
 * Cached per request: the layout (feedback button) and the navigation both need it.
 */
export const fetchSession = cache(async (): Promise<Session> => {
  try {
    const { data } = await api.GET("/auth/session", {
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    return data ?? { state: "ANONYMOUS" };
  } catch {
    return { state: "ANONYMOUS" };
  }
});

export type SignupPolicy = components["schemas"]["SignupPolicy"];

/** Invite-only during the closed beta (ADR 0030). Falls back to "open" if the API is unreachable. */
export async function fetchSignupPolicy(): Promise<SignupPolicy> {
  try {
    const { data } = await api.GET("/auth/signup-policy", { cache: "no-store", signal: AbortSignal.timeout(5000) });
    return data ?? { inviteOnly: false, termsVersion: "" };
  } catch {
    return { inviteOnly: false, termsVersion: "" };
  }
}

/** Workspace settings plus ETag for optimistic locking, or null if not signed in. */
export async function fetchWorkspace(): Promise<{ workspace: Workspace; etag: string } | null> {
  const { data, response } = await api.GET("/workspace", {
    headers: await sessionHeaders(),
    cache: "no-store",
    signal: AbortSignal.timeout(5000),
  });
  return data ? { workspace: data, etag: response.headers.get("ETag") ?? "" } : null;
}

export type DocumentList = components["schemas"]["DocumentList"];
export type DocumentSummary = components["schemas"]["DocumentSummary"];
export type Document = components["schemas"]["Document"];
export type InboxSummary = components["schemas"]["InboxSummary"];

async function authed<T>(
  call: (init: { headers: HeadersInit; cache: "no-store"; signal: AbortSignal }) => Promise<{ data?: T }>,
): Promise<T | null> {
  try {
    const { data } = await call({
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    return data ?? null;
  } catch {
    return null;
  }
}

export type DocumentFilter = Omit<NonNullable<paths["/documents"]["get"]["parameters"]["query"]>, "limit" | "cursor">;
export type InvoiceView = components["schemas"]["InvoiceView"];
export type ViewSection = components["schemas"]["ViewSection"];

/** First page of documents, newest first, with optional filters (null if not signed in). */
export async function fetchDocuments(filter: DocumentFilter = {}): Promise<DocumentList | null> {
  return authed((init) => api.GET("/documents", { ...init, params: { query: { ...filter, limit: 50 } } }));
}

/**
 * The invoice view (VW-01) in the app's language (VW-07; German if no English view exists yet),
 * or null if there is none (not an e-invoice, or still being read).
 */
export async function fetchDocumentView(documentId: string, lang: "de" | "en" = "de"): Promise<InvoiceView | null> {
  return authed((init) =>
    api.GET("/documents/{documentId}/view", { ...init, params: { path: { documentId }, query: { lang } } }),
  );
}

export async function fetchDocument(documentId: string): Promise<Document | null> {
  return authed((init) => api.GET("/documents/{documentId}", { ...init, params: { path: { documentId } } }));
}

/** Counts for the badge in the navigation. */
export async function fetchInboxSummary(): Promise<InboxSummary | null> {
  return authed((init) => api.GET("/inbox/summary", init));
}

export async function fetchIntakeAddress(): Promise<string | null> {
  const data = await authed((init) => api.GET("/intake/address", init));
  return data?.address ?? null;
}

export async function fetchNotificationSettings(): Promise<components["schemas"]["NotificationSettings"] | null> {
  return authed((init) => api.GET("/notification-settings", init));
}

export type Invoice = components["schemas"]["Invoice"];
export type InvoiceList = components["schemas"]["InvoiceList"];
export type InvoiceSettings = components["schemas"]["InvoiceSettings"];
export type Customer = components["schemas"]["Customer"];
export type Article = components["schemas"]["Article"];

/** Outgoing invoices and drafts, newest first (null if not signed in). */
export async function fetchInvoices(): Promise<InvoiceList | null> {
  return authed((init) => api.GET("/invoices", { ...init, params: { query: { limit: 50 } } }));
}

/** One invoice or draft with its ETag (for autosave with If-Match), or null. */
export async function fetchInvoice(invoiceId: string): Promise<{ invoice: Invoice; etag: string } | null> {
  try {
    const { data, response } = await api.GET("/invoices/{invoiceId}", {
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
      params: { path: { invoiceId } },
    });
    return data ? { invoice: data, etag: response.headers.get("ETag") ?? "" } : null;
  } catch {
    return null;
  }
}

export async function fetchInvoiceSettings(): Promise<{ settings: InvoiceSettings; etag: string } | null> {
  try {
    const { data, response } = await api.GET("/invoice-settings", {
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    return data ? { settings: data, etag: response.headers.get("ETag") ?? "" } : null;
  } catch {
    return null;
  }
}

export async function fetchCustomers(includeArchived = false): Promise<Customer[]> {
  const data = await authed((init) =>
    api.GET("/customers", { ...init, params: { query: { includeArchived, limit: 200 } } }),
  );
  return data?.items ?? [];
}

export async function fetchArticles(includeArchived = false): Promise<Article[]> {
  const data = await authed((init) =>
    api.GET("/articles", { ...init, params: { query: { includeArchived, limit: 200 } } }),
  );
  return data?.items ?? [];
}

export type Member = components["schemas"]["Member"];
export type Invitation = components["schemas"]["Invitation"];
export type Export = components["schemas"]["Export"];
export type DatevSettings = components["schemas"]["DatevSettings"];
export type Comment = components["schemas"]["Comment"];

export async function fetchMembers(): Promise<Member[]> {
  const data = await authed((init) => api.GET("/members", init));
  return data?.items ?? [];
}

/** Invitations (owners only; empty for other roles). */
export async function fetchInvitations(): Promise<Invitation[]> {
  const data = await authed((init) => api.GET("/invitations", init));
  return data?.items ?? [];
}

export async function fetchExports(): Promise<Export[]> {
  const data = await authed((init) => api.GET("/exports", init));
  return data?.items ?? [];
}

export async function fetchDatevSettings(): Promise<{ settings: DatevSettings; etag: string } | null> {
  try {
    const { data, response } = await api.GET("/datev-settings", {
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    return data ? { settings: data, etag: response.headers.get("ETag") ?? "" } : null;
  } catch {
    return null;
  }
}

export async function fetchComments(documentId: string): Promise<Comment[]> {
  const data = await authed((init) =>
    api.GET("/documents/{documentId}/comments", { ...init, params: { path: { documentId } } }),
  );
  return data?.items ?? [];
}

export type BillingStatus = components["schemas"]["BillingStatus"];
export type BillingInvoice = components["schemas"]["BillingInvoice"];

export async function fetchBilling(): Promise<BillingStatus | null> {
  return authed((init) => api.GET("/billing", init));
}

export async function fetchBillingInvoices(): Promise<BillingInvoice[]> {
  const data = await authed((init) => api.GET("/billing/invoices", init));
  return data?.items ?? [];
}

export type IntakeSettings = components["schemas"]["IntakeSettings"];
export type QuarantinedMail = components["schemas"]["QuarantinedMail"];
export type RecurringInvoice = components["schemas"]["RecurringInvoice"];

/** Sender allow-list (IN-06), or null if not signed in. */
export async function fetchIntakeSettings(): Promise<IntakeSettings | null> {
  return authed((init) => api.GET("/intake/settings", init));
}

export async function fetchQuarantine(): Promise<QuarantinedMail[]> {
  const data = await authed((init) => api.GET("/intake/quarantine", init));
  return data?.items ?? [];
}

/** Recurring invoices (CR-09). */
export async function fetchRecurringInvoices(): Promise<RecurringInvoice[]> {
  const data = await authed((init) => api.GET("/recurring-invoices", init));
  return data?.items ?? [];
}

// MARA (ADR 0036): work orders, sites, staff, checklist templates.
export type WorkOrder = components["schemas"]["WorkOrder"];
export type WorkOrderStatus = components["schemas"]["WorkOrderStatus"];
export type Site = components["schemas"]["Site"];
export type StaffLevel = components["schemas"]["StaffLevel"];
export type StaffMember = components["schemas"]["StaffMember"];
export type Device = components["schemas"]["Device"];
export type ChecklistTemplate = components["schemas"]["ChecklistTemplate"];

export async function fetchWorkOrders(status?: WorkOrderStatus): Promise<WorkOrder[]> {
  const data = await authed((init) =>
    api.GET("/work-orders", { ...init, params: { query: { ...(status ? { status } : {}), limit: 500 } } }),
  );
  return data?.items ?? [];
}

/** One work order plus ETag for optimistic locking, or null. */
export type SyncConflict = components["schemas"]["SyncConflict"];

/** Notes on concurrent changes from the MARA app to one work order or customer (ADR 0038). */
export async function fetchSyncConflicts(entityId: string): Promise<SyncConflict[]> {
  return (
    (await authed((init) => api.GET("/sync-conflicts", { ...init, params: { query: { entityId } } })))?.items ?? []
  );
}

export type WorkOrderReport = components["schemas"]["WorkOrderReport"];

/** The job report recorded in the MARA app for a work order (MARA Stage 5); null on errors. */
export async function fetchWorkOrderReport(workOrderId: string): Promise<WorkOrderReport | null> {
  return authed((init) => api.GET("/work-orders/{workOrderId}/report", { ...init, params: { path: { workOrderId } } }));
}

export type ReportVerification = components["schemas"]["ReportVerification"];

/** The public check of a signed report by its code (no session); null if unknown. */
export async function fetchReportVerification(code: string): Promise<ReportVerification | null> {
  try {
    const { data } = await api.GET("/public/report-verifications/{code}", {
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
      params: { path: { code } },
    });
    return data ?? null;
  } catch {
    return null;
  }
}

export async function fetchWorkOrder(workOrderId: string): Promise<{ workOrder: WorkOrder; etag: string } | null> {
  try {
    const { data, response } = await api.GET("/work-orders/{workOrderId}", {
      headers: await sessionHeaders(),
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
      params: { path: { workOrderId } },
    });
    return data ? { workOrder: data, etag: response.headers.get("ETag") ?? "" } : null;
  } catch {
    return null;
  }
}

export async function fetchStaff(): Promise<StaffMember[]> {
  return (await authed((init) => api.GET("/staff", init)))?.items ?? [];
}

/** Phones signed in to the MARA app (owner only). */
export async function fetchDevices(): Promise<Device[]> {
  return (await authed((init) => api.GET("/devices", init)))?.items ?? [];
}

export async function fetchStaffLevels(includeArchived = false): Promise<StaffLevel[]> {
  return (
    (await authed((init) => api.GET("/staff-levels", { ...init, params: { query: { includeArchived } } })))?.items ?? []
  );
}

export async function fetchChecklistTemplates(includeArchived = false): Promise<ChecklistTemplate[]> {
  return (
    (await authed((init) => api.GET("/checklist-templates", { ...init, params: { query: { includeArchived } } })))
      ?.items ?? []
  );
}
