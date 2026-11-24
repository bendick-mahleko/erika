import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import Link from "next/link";
import { UserMenu } from "@/components/UserMenu";
import { fetchBilling, fetchSession } from "@/lib/api/client";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Layout");
  return { title: t("productName"), description: t("productTagline") };
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getLocale();
  const t = await getTranslations("Layout");
  const tf = await getTranslations("Footer");
  // Public status page on the ops server (NF-06); read at runtime, so one image fits all environments.
  const statusUrl = process.env.ERIKA_STATUS_URL;
  const footerLink = "underline-offset-4 hover:underline";
  const session = await fetchSession();
  const closed = session.state === "AUTHENTICATED" && session.workspace?.closed === true;
  const tn = await getTranslations("Nav");
  const billing = session.state === "AUTHENTICATED" && !closed ? await fetchBilling() : null;
  const billingBanner =
    billing?.state === "READ_ONLY" ? "readOnlyBanner" : billing?.state === "PAST_DUE" ? "pastDueBanner" : null;

  return (
    <html lang={locale}>
      <body className="flex min-h-screen flex-col">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:rounded focus:bg-white focus:px-4 focus:py-2 focus:shadow"
        >
          {t("skipToContent")}
        </a>
        <NextIntlClientProvider>
          <header className="border-b border-slate-200 bg-brand-900 text-white">
            <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-4 py-4">
              <Link href="/" className="text-xl font-semibold tracking-wide text-white">
                {t("productName")}
              </Link>
              <span className="hidden text-sm text-slate-200 lg:inline">{t("productTagline")}</span>
              <UserMenu />
            </div>
          </header>
          <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
            {closed && (
              <p
                role="status"
                className="mb-6 rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900"
                data-testid="closed-banner"
              >
                {tn("closedBanner")}
              </p>
            )}
            {billingBanner && (
              <p
                role="status"
                className="mb-6 rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900"
                data-testid="billing-banner"
              >
                {tn(billingBanner)}{" "}
                <Link href="/settings/billing" className="underline">
                  {tn("billingLink")}
                </Link>
              </p>
            )}
            {children}
          </main>
        </NextIntlClientProvider>
        <footer className="border-t border-slate-200 bg-slate-50 text-sm text-slate-700">
          <nav aria-label={tf("label")} className="mx-auto flex max-w-5xl flex-wrap gap-x-5 gap-y-2 px-4 py-4">
            <Link href="/impressum" className={footerLink}>
              {tf("imprint")}
            </Link>
            <Link href="/datenschutz" className={footerLink}>
              {tf("privacy")}
            </Link>
            <Link href="/nutzungsbedingungen" className={footerLink}>
              {tf("terms")}
            </Link>
            <Link href="/auftragsverarbeitung" className={footerLink}>
              {tf("dpa")}
            </Link>
            <Link href="/unterauftragsverarbeiter" className={footerLink}>
              {tf("subprocessors")}
            </Link>
            {statusUrl && (
              <a href={statusUrl} className={footerLink}>
                {tf("status")}
              </a>
            )}
          </nav>
        </footer>
      </body>
    </html>
  );
}
