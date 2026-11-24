import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchInvoiceSettings, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { InvoiceSettingsForm } from "./InvoiceSettingsForm";
import { LogoSettings } from "./LogoSettings";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("InvoiceSettings");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function InvoiceSettingsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const result = await fetchInvoiceSettings();
  if (!result) redirect("/login");
  const t = await getTranslations("InvoiceSettings");
  return (
    <div className="space-y-6">
      <SettingsNav current="invoices" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {/* The back end allows only owners to change these; this only avoids offering what would be refused. */}
      <InvoiceSettingsForm initial={result.settings} etag={result.etag} canEdit={session.role === "OWNER"} />
      <LogoSettings initialHasLogo={result.settings.hasLogo ?? false} canEdit={session.role === "OWNER"} />
    </div>
  );
}
