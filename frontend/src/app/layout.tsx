import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getLocale, getTranslations } from "next-intl/server";
import Link from "next/link";
import { UserMenu } from "@/components/UserMenu";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Layout");
  return { title: t("productName"), description: t("productTagline") };
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getLocale();
  const t = await getTranslations("Layout");

  return (
    <html lang={locale}>
      <body className="min-h-screen">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:rounded focus:bg-white focus:px-4 focus:py-2 focus:shadow"
        >
          {t("skipToContent")}
        </a>
        <header className="border-b border-slate-200 bg-brand-900 text-white">
          <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-4 py-4">
            <Link href="/" className="text-xl font-semibold tracking-wide text-white">
              {t("productName")}
            </Link>
            <span className="hidden text-sm text-slate-200 lg:inline">{t("productTagline")}</span>
            <UserMenu />
          </div>
        </header>
        <NextIntlClientProvider>
          <main id="main" className="mx-auto max-w-5xl px-4 py-8">
            {children}
          </main>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
