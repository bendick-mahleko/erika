import { NextResponse, type NextRequest } from "next/server";

/**
 * Content Security Policy with a fresh nonce per request (ASVS 14.4.3, ADR 0034). Next.js reads
 * the nonce from the request's CSP header and puts it on its own scripts; every page is rendered
 * per request (the layout reads the session), so each response gets its own nonce.
 * `ERIKA_CSP_CONNECT_SRC` adds origins the browser talks to directly: the object store for
 * pre-signed uploads.
 */
function contentSecurityPolicy(nonce: string): string {
  const dev = process.env.NODE_ENV === "development";
  const connect = ["'self'", ...(process.env.ERIKA_CSP_CONNECT_SRC ?? "").split(/\s+/).filter(Boolean)];
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    // Next.js and React set a few inline style attributes; no external styles are loaded.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src ${connect.join(" ")}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");
}

/**
 * - `/api/v1/*`: forwarded to the back end, so the session and CSRF cookies are first-party for
 *   the web app's origin. Read at runtime, so one image fits every environment. In staging and
 *   production the reverse proxy (Caddy) routes /api/* to the back end directly.
 * - pages: the CSP header above.
 */
export function proxy(request: NextRequest) {
  if (request.nextUrl.pathname.startsWith("/api/v1/")) {
    const apiBase = process.env.ERIKA_API_INTERNAL_URL ?? "http://localhost:8080";
    const target = new URL(request.nextUrl.pathname + request.nextUrl.search, apiBase);
    return NextResponse.rewrite(target);
  }
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const csp = contentSecurityPolicy(nonce);
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  matcher: [
    "/api/v1/:path*",
    // Pages; not static assets or prefetches.
    {
      source: "/((?!_next/static|_next/image|favicon.ico|icon.svg).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
