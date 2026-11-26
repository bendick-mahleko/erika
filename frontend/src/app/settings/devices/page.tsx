import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchDevices, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { DevicesManager } from "./DevicesManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Devices");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Phones signed in to the MARA app; the owner locks lost ones out (M-NF-07, ADR 0037). */
export default async function DevicesPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const isOwner = session.role === "OWNER";
  const devices = isOwner ? await fetchDevices() : [];
  const t = await getTranslations("Devices");
  return (
    <div className="space-y-6">
      <SettingsNav current="devices" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {isOwner ? <DevicesManager initialDevices={devices} /> : <p className="text-slate-700">{t("ownerOnly")}</p>}
    </div>
  );
}
