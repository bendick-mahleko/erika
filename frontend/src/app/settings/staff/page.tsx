import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchSession, fetchStaff, fetchStaffLevels } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { StaffManager } from "./StaffManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Staff");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function StaffPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const [levels, staff] = await Promise.all([fetchStaffLevels(true), fetchStaff()]);
  const t = await getTranslations("Staff");
  return (
    <div className="space-y-6">
      <SettingsNav current="staff" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <StaffManager initialLevels={levels} initialStaff={staff} isOwner={session.role === "OWNER"} />
    </div>
  );
}
