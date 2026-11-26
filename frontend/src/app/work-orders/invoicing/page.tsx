import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchBillableReports, fetchSession } from "@/lib/api/client";
import { WorkOrdersNav } from "../WorkOrdersNav";
import { ReportInvoicing } from "./ReportInvoicing";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("ReportInvoicing");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** MARA Stage 8 (M-IV-01): signed reports of one customer become an e-invoice through the invoice creator. */
export default async function ReportInvoicingPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const canEdit = session.role === "OWNER" || session.role === "MEMBER";
  if (!canEdit) notFound();
  const reports = await fetchBillableReports();
  const t = await getTranslations("ReportInvoicing");
  const today = new Date().toISOString().slice(0, 10);
  const monthStart = `${today.slice(0, 8)}01`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <WorkOrdersNav current="invoicing" canEdit={canEdit} />
      <ReportInvoicing reports={reports} />
      {session.role === "OWNER" && (
        <section aria-labelledby="times-export" className="space-y-3 border-t border-slate-200 pt-4">
          <h2 id="times-export" className="text-lg font-semibold">
            {t("timesTitle")}
          </h2>
          <p className="max-w-3xl text-slate-700">{t("timesIntro")}</p>
          {/* A download: GET with the dates as query parameters (the API sends it as an attachment). */}
          <form method="get" action="/api/v1/reports/time-entries.csv" className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <label htmlFor="times-from" className="block font-medium">
                {t("timesFrom")}
              </label>
              <input
                id="times-from"
                name="from"
                type="date"
                required
                defaultValue={monthStart}
                className="rounded border px-2 py-1"
              />
            </div>
            <div className="space-y-1">
              <label htmlFor="times-to" className="block font-medium">
                {t("timesTo")}
              </label>
              <input
                id="times-to"
                name="to"
                type="date"
                required
                defaultValue={today}
                className="rounded border px-2 py-1"
              />
            </div>
            <button type="submit" className="rounded border border-brand-700 px-3 py-1 text-brand-700">
              {t("timesDownload")}
            </button>
          </form>
          <p className="text-sm">
            <Link href="/settings/report-invoicing" className="text-brand-700 underline">
              {t("settingsLink")}
            </Link>
          </p>
        </section>
      )}
    </div>
  );
}
