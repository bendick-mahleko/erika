import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchArticles, fetchSession } from "@/lib/api/client";
import { ArticlesManager } from "./ArticlesManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Articles");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function ArticlesPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const t = await getTranslations("Articles");
  const tc = await getTranslations("Creator");
  return (
    <div className="space-y-6">
      <p>
        <Link href="/invoices" className="text-brand-700 underline">
          {tc("back")}
        </Link>
      </p>
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 text-slate-700">{t("intro")}</p>
      </div>
      <ArticlesManager initial={await fetchArticles(true)} canEdit={session.role !== "ADVISOR"} />
    </div>
  );
}
