"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { browserApi } from "@/lib/api/browser";

const LOCALES = ["de", "en"] as const;
const ONE_YEAR = 60 * 60 * 24 * 365;

/**
 * Deutsch / English (NF-13). The choice is kept in the NEXT_LOCALE cookie (read by
 * src/i18n/request.ts) and, when signed in, saved for the user so emails use it too.
 */
export function LanguageSwitcher({ signedIn }: { signedIn: boolean }) {
  const t = useTranslations("Language");
  const current = useLocale();
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submitter = (event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null;
    const locale = submitter?.value as (typeof LOCALES)[number] | undefined;
    if (!locale || locale === current) return;
    setBusy(true);
    document.cookie = `NEXT_LOCALE=${locale}; path=/; max-age=${ONE_YEAR}; samesite=lax`;
    if (signedIn) {
      // The cookie alone already switches the app; a failure here only affects emails.
      await browserApi.POST("/auth/locale", { body: { locale } }).catch(() => undefined);
    }
    setBusy(false);
    router.refresh();
  }

  return (
    <form method="post" onSubmit={submit} aria-label={t("label")} className="flex items-center gap-1">
      {LOCALES.map((locale) => (
        <button
          key={locale}
          type="submit"
          name="locale"
          value={locale}
          lang={locale}
          disabled={busy}
          aria-pressed={locale === current}
          title={t(locale)}
          className={
            locale === current
              ? "rounded bg-white px-2 py-0.5 text-xs font-semibold text-brand-900"
              : "rounded px-2 py-0.5 text-xs text-white underline-offset-4 hover:underline"
          }
        >
          <span aria-hidden="true">{locale.toUpperCase()}</span>
          <span className="sr-only">{t(locale)}</span>
        </button>
      ))}
    </form>
  );
}
