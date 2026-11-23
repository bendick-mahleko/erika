import Link from "next/link";
import { getTranslations } from "next-intl/server";
import type { DocumentFilter } from "@/lib/api/client";

const STATUSES = ["NEW", "CHECKED", "APPROVED", "PAID", "DISPUTED", "BOOKED"] as const;

/**
 * Search and filters (AR-04) as a plain GET form: works without JavaScript, and the URL of
 * a filtered list can be bookmarked or shared.
 */
export async function InboxFilters({ filter }: { filter: DocumentFilter }) {
  const t = await getTranslations("Inbox");
  const td = await getTranslations("Document");
  const input = "block w-full rounded border border-slate-400 px-3 py-2";

  return (
    <div role="search" aria-label={t("search")}>
      <form method="get" action="/inbox" className="grid gap-3 rounded-lg border border-slate-200 p-4 sm:grid-cols-6">
        <div className="sm:col-span-6">
          <label htmlFor="q" className="block font-medium">
            {t("search")}
          </label>
          <input id="q" name="q" type="search" defaultValue={filter.q} maxLength={200} className={input} />
          <p className="mt-1 text-sm text-slate-600">{t("searchHint")}</p>
        </div>
        <div className="sm:col-span-2">
          <label htmlFor="status" className="block font-medium">
            {t("filterStatus")}
          </label>
          <select id="status" name="status" defaultValue={filter.status ?? ""} className={input}>
            <option value="">{t("allStatuses")}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {td(`status.${s}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="sm:col-span-2">
          <label htmlFor="from" className="block font-medium">
            {t("filterFrom")}
          </label>
          <input id="from" name="from" type="date" defaultValue={filter.from} className={input} />
        </div>
        <div className="sm:col-span-2">
          <label htmlFor="to" className="block font-medium">
            {t("filterTo")}
          </label>
          <input id="to" name="to" type="date" defaultValue={filter.to} className={input} />
        </div>
        {filter.tag && <input type="hidden" name="tag" value={filter.tag} />}
        <div className="flex flex-wrap items-center gap-3 sm:col-span-6">
          <button type="submit" className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900">
            {t("applyFilters")}
          </button>
          {Object.keys(filter).length > 0 && (
            <Link href="/inbox" className="text-brand-700 underline">
              {t("resetFilters")}
            </Link>
          )}
          {filter.tag && <span className="text-sm">{t("tagFilter", { tag: filter.tag })}</span>}
        </div>
      </form>
    </div>
  );
}
