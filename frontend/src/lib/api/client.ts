import "server-only";
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

/** Sign-in state of the current browser session; ANONYMOUS if the API can't be reached. */
export async function fetchSession(): Promise<Session> {
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
