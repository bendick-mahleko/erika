import { getTranslations } from "next-intl/server";
import { HealthPanel } from "@/components/HealthPanel";
import { fetchHealth } from "@/lib/api/client";

// Always render on request: the page shows live system status.
export const dynamic = "force-dynamic";

export default async function HomePage() {
  const t = await getTranslations("Home");
  const health = await fetchHealth();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 text-slate-700">{t("intro")}</p>
      </div>
      <HealthPanel health={health} />
    </div>
  );
}
