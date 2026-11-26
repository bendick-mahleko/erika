import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchOverdueInvoices, fetchSession } from "@/lib/api/client";
import { OverdueList } from "./OverdueList";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Overdue");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** M-IV-06: issued invoices past their due date; a payment reminder, then up to two Mahnungen, by email. */
export default async function OverdueInvoicesPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const items = await fetchOverdueInvoices();
  const t = await getTranslations("Overdue");
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
      <OverdueList initial={items} canEdit={session.role === "OWNER" || session.role === "MEMBER"} />
    </div>
  );
}
