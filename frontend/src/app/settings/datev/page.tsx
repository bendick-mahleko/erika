import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchDatevSettings, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { DatevForm } from "./DatevForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Datev");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function DatevSettingsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const result = await fetchDatevSettings();
  if (!result) redirect("/login");
  const t = await getTranslations("Datev");
  return (
    <div className="space-y-6">
      <SettingsNav current="datev" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <DatevForm initial={result.settings} etag={result.etag} canEdit={session.role === "OWNER"} />
    </div>
  );
}
