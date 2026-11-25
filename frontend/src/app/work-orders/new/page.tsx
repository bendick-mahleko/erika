import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchChecklistTemplates, fetchCustomers, fetchSession, fetchStaff } from "@/lib/api/client";
import { WorkOrdersNav } from "../WorkOrdersNav";
import { WorkOrderForm } from "../WorkOrderForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("WorkOrders");
  return { title: `${t("new")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function NewWorkOrderPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  if (session.role !== "OWNER" && session.role !== "MEMBER") redirect("/work-orders");
  const [customers, staff, templates] = await Promise.all([fetchCustomers(), fetchStaff(), fetchChecklistTemplates()]);
  const t = await getTranslations("WorkOrders");
  return (
    <div className="space-y-6">
      <p>
        <Link href="/work-orders" className="text-brand-700 underline">
          {t("back")}
        </Link>
      </p>
      <h1 className="text-2xl font-semibold">{t("new")}</h1>
      <WorkOrdersNav current="new" canEdit />
      <WorkOrderForm customers={customers} staff={staff} templates={templates} canEdit />
    </div>
  );
}
