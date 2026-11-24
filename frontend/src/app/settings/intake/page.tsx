import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchIntakeAddress, fetchIntakeSettings, fetchQuarantine, fetchSession } from "@/lib/api/client";
import { IntakeAddress } from "@/app/inbox/IntakeAddress";
import { SettingsNav } from "@/components/SettingsNav";
import { IntakeSettingsManager } from "./IntakeSettingsManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("IntakeSettings");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Email intake: the address, the sender allow-list and the quarantine (IN-06). */
export default async function IntakeSettingsPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const [settings, quarantine, address] = await Promise.all([
    fetchIntakeSettings(),
    fetchQuarantine(),
    fetchIntakeAddress(),
  ]);
  if (!settings) redirect("/login");
  const t = await getTranslations("IntakeSettings");
  return (
    <div className="space-y-6">
      <SettingsNav current="intake" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {address && <IntakeAddress address={address} />}
      {/* The back end enforces the roles as well; this only avoids offering what would be refused. */}
      <IntakeSettingsManager
        initial={settings}
        quarantine={quarantine}
        canConfigure={session.role === "OWNER"}
        canDecide={session.role !== "ADVISOR"}
      />
    </div>
  );
}
