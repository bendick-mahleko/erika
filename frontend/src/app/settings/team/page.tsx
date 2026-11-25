import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchInvitations, fetchMembers, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { TeamManager } from "./TeamManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Team");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function TeamPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED" || !session.user) redirect("/login");
  const isOwner = session.role === "OWNER";
  const [members, invitations] = await Promise.all([
    fetchMembers(),
    isOwner ? fetchInvitations() : Promise.resolve([]),
  ]);
  const t = await getTranslations("Team");
  return (
    <div className="space-y-6">
      <SettingsNav current="team" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <TeamManager
        initialMembers={members}
        initialInvitations={invitations}
        currentUserId={session.user.id}
        isOwner={isOwner}
        mara={session.features?.mara === true}
      />
    </div>
  );
}
