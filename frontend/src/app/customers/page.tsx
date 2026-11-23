import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchCustomers, fetchSession } from "@/lib/api/client";
import { CustomersManager } from "./CustomersManager";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Customers");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function CustomersPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const t = await getTranslations("Customers");
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
      <CustomersManager initial={await fetchCustomers(true)} canEdit={session.role !== "ADVISOR"} />
    </div>
  );
}
