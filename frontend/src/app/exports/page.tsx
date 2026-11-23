import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchDatevSettings, fetchExports, fetchSession } from "@/lib/api/client";
import { ExportsManager } from "./ExportsManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Exports");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Previous calendar month as the default period. */
function lastMonth(): { from: string; to: string } {
  const now = new Date();
  const from = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
}

export default async function ExportsPage({
  searchParams,
}: {
  searchParams: Promise<{ highlight?: string | string[] }>;
}) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const raw = (await searchParams).highlight;
  const [exports, datev] = await Promise.all([fetchExports(), fetchDatevSettings()]);
  const t = await getTranslations("Exports");
  const isOwner = session.role === "OWNER";
  const period = lastMonth();
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {datev && !datev.settings.complete && (
        <p className="rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900" data-testid="datev-missing">
          {t("datevMissing")}{" "}
          {isOwner ? (
            <Link href="/settings/datev" className="underline">
              {t("datevMissingLink")}
            </Link>
          ) : (
            t("datevMissingAsk")
          )}
        </p>
      )}
      <ExportsManager
        initial={exports}
        isOwner={isOwner}
        highlight={Array.isArray(raw) ? raw[0] : raw}
        defaultFrom={period.from}
        defaultTo={period.to}
      />
    </div>
  );
}
