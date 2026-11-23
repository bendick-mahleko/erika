import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchIntakeAddress, fetchNotificationSettings, fetchSession, fetchWorkspace } from "@/lib/api/client";
import { IntakeAddress } from "@/app/inbox/IntakeAddress";
import { SettingsNav } from "@/components/SettingsNav";
import { CloseAccount } from "./CloseAccount";
import { NotificationForm } from "./NotificationForm";
import { WorkspaceForm } from "./WorkspaceForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Workspace");
  return { title: `${t("title")} – ERIKA` };
}

export default async function WorkspaceSettingsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const result = await fetchWorkspace();
  if (!result) redirect("/login");
  const t = await getTranslations("Workspace");
  const [address, notifications] = await Promise.all([fetchIntakeAddress(), fetchNotificationSettings()]);

  return (
    <div className="space-y-6">
      <SettingsNav current="workspace" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 text-slate-700">{t("intro")}</p>
      </div>
      {/* The back end enforces the role as well; this only avoids offering what would be refused. */}
      <WorkspaceForm initial={result.workspace} etag={result.etag} canEdit={session.role === "OWNER"} />
      {address && <IntakeAddress address={address} />}
      {notifications && (
        <NotificationForm initial={notifications.emailOnNewDocuments} canEdit={session.role !== "ADVISOR"} />
      )}
      {session.role === "OWNER" && <CloseAccount workspace={result.workspace} />}
    </div>
  );
}
