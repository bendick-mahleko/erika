import Link from "next/link";
import { getTranslations } from "next-intl/server";

const SECTIONS = [
  ["workspace", "/settings/workspace"],
  ["invoices", "/settings/invoices"],
  ["team", "/settings/team"],
  ["datev", "/settings/datev"],
] as const;

/** Links between the settings pages; the current one is marked for assistive technology. */
export async function SettingsNav({ current }: { current: (typeof SECTIONS)[number][0] }) {
  const t = await getTranslations("Settings");
  return (
    <nav aria-label={t("nav")} className="flex flex-wrap gap-2 border-b border-slate-200 pb-3 text-sm">
      {SECTIONS.map(([key, href]) => (
        <Link
          key={key}
          href={href}
          aria-current={key === current ? "page" : undefined}
          className={
            key === current
              ? "rounded bg-brand-700 px-3 py-1 font-medium text-white"
              : "rounded px-3 py-1 text-brand-700 underline-offset-4 hover:underline"
          }
        >
          {t(key)}
        </Link>
      ))}
    </nav>
  );
}
