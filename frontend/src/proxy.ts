import { NextResponse, type NextRequest } from "next/server";

/**
 * Forwards browser calls to /api/v1/* to the back end, so the session and CSRF cookies
 * are first-party for the web app's origin. Read at runtime (not build time), so the
 * same image works in every environment. In staging and production the reverse proxy
 * (Caddy) routes /api/* to the back end directly and this never runs for those paths.
 */
export function proxy(request: NextRequest) {
  const apiBase = process.env.ERIKA_API_INTERNAL_URL ?? "http://localhost:8080";
  const target = new URL(request.nextUrl.pathname + request.nextUrl.search, apiBase);
  return NextResponse.rewrite(target);
}

export const config = {
  matcher: "/api/v1/:path*",
};
