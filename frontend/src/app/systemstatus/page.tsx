import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { HealthPanel } from "@/components/HealthPanel";
import { fetchHealth } from "@/lib/api/client";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Home");
  return { title: `${t("statusTitle")} – ERIKA` };
}

/** Live status of the system parts, as the API reports it (the public status page is on the ops server). */
export default async function SystemStatusPage() {
  const t = await getTranslations("Home");
  const health = await fetchHealth();
  const statusUrl = process.env.ERIKA_STATUS_URL;
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("statusTitle")}</h1>
        <p className="mt-2 text-slate-700">{t("statusIntro")}</p>
        {statusUrl && (
          <p className="mt-2">
            <a href={statusUrl} className="text-brand-700 underline">
              {t("statusPage")}
            </a>
          </p>
        )}
      </div>
      <HealthPanel health={health} />
    </div>
  );
}
