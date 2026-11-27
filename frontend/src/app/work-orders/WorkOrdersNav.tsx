import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { fetchMaraAddon } from "@/lib/api/client";

/** Links within the work-order area (MARA); the current page is marked for assistive technology. */
export async function WorkOrdersNav({
  current,
  canEdit,
}: {
  current: "list" | "calendar" | "new" | "invoicing" | null;
  canEdit: boolean;
}) {
  const t = await getTranslations("WorkOrders");
  const tm = await getTranslations("MaraAddon");
  // MARA Stage 10: without the add-on (or after it), everything stays readable; a hint says why changes fail.
  const addon = await fetchMaraAddon();
  const blocked = addon && (addon.state === "NONE" || addon.state === "READ_ONLY");
  const items = [
    ["list", "/work-orders", t("list")],
    ["calendar", "/work-orders/calendar", t("calendar")],
    ...(canEdit ? [["new", "/work-orders/new", t("new")] as const] : []),
    ...(canEdit ? [["invoicing", "/work-orders/invoicing", t("invoicing")] as const] : []),
  ] as const;
  return (
    <>
      {blocked && (
        <p className="rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900" data-testid="mara-blocked">
          {tm(`banner.${addon.state}`)}{" "}
          <Link href="/settings/billing" className="underline">
            {tm("bannerLink")}
          </Link>
        </p>
      )}
      <nav aria-label={t("title")} className="flex flex-wrap gap-2 border-b border-slate-200 pb-3 text-sm">
        {items.map(([key, href, label]) => (
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
            {label}
          </Link>
        ))}
      </nav>
    </>
  );
}
