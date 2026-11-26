import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchReportInvoicingSettings, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { ReportInvoicingSettingsForm } from "./ReportInvoicingSettingsForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("ReportInvoicingSettings");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** MARA Stage 8 (M-BR-04, M-BR-05): billing unit, default hourly rate and travel; owners change them. */
export default async function ReportInvoicingSettingsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const settings = await fetchReportInvoicingSettings();
  const t = await getTranslations("ReportInvoicingSettings");
  return (
    <div className="space-y-6">
      <SettingsNav current="reportInvoicing" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {settings && <ReportInvoicingSettingsForm initial={settings} canEdit={session.role === "OWNER"} />}
    </div>
  );
}
