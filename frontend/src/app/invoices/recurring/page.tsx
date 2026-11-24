import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchRecurringInvoices, fetchSession } from "@/lib/api/client";
import { RecurringManager } from "./RecurringManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Recurring");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Recurring invoices (CR-09): ERIKA creates a draft on each run date; a person checks and issues it. */
export default async function RecurringInvoicesPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const items = await fetchRecurringInvoices();
  const t = await getTranslations("Recurring");
  const tc = await getTranslations("Creator");
  return (
    <div className="space-y-6">
      <p>
        <Link href="/invoices" className="text-brand-700 underline">
          {tc("back")}
        </Link>
      </p>
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <RecurringManager initial={items} canEdit={session.role !== "ADVISOR"} />
    </div>
  );
}
