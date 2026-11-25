import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchChecklistTemplates, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { ChecklistsManager } from "./ChecklistsManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Checklists");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function ChecklistsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const templates = await fetchChecklistTemplates(true);
  const t = await getTranslations("Checklists");
  return (
    <div className="space-y-6">
      <SettingsNav current="checklists" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <ChecklistsManager initial={templates} canEdit={session.role === "OWNER" || session.role === "MEMBER"} />
    </div>
  );
}
