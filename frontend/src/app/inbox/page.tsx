import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchDocuments, fetchIntakeAddress, fetchSession } from "@/lib/api/client";
import { IntakeAddress } from "./IntakeAddress";
import { InboxClient } from "./InboxClient";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Inbox");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function InboxPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const [documents, address] = await Promise.all([fetchDocuments(), fetchIntakeAddress()]);
  const t = await getTranslations("Inbox");

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {address && <IntakeAddress address={address} />}
      {/* Tax advisors only read (EX-02); the back end refuses uploads from them anyway. */}
      <InboxClient
        initial={documents?.items ?? []}
        nextCursor={documents?.nextCursor}
        canUpload={session.role !== "ADVISOR"}
      />
    </div>
  );
}
