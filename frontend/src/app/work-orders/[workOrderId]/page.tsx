import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchChecklistTemplates, fetchCustomers, fetchSession, fetchStaff, fetchWorkOrder } from "@/lib/api/client";
import { statusClass } from "@/lib/workorders";
import { WorkOrdersNav } from "../WorkOrdersNav";
import { WorkOrderForm } from "../WorkOrderForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("WorkOrders");
  return { title: `${t("workTitle")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function WorkOrderPage({ params }: { params: Promise<{ workOrderId: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const { workOrderId } = await params;
  const [loaded, customers, staff, templates] = await Promise.all([
    fetchWorkOrder(workOrderId),
    fetchCustomers(true),
    fetchStaff(),
    fetchChecklistTemplates(true),
  ]);
  if (!loaded) notFound();
  const t = await getTranslations("WorkOrders");
  const canEdit = session.role === "OWNER" || session.role === "MEMBER";
  const { workOrder } = loaded;
  return (
    <div className="space-y-6">
      <p>
        <Link href="/work-orders" className="text-brand-700 underline">
          {t("back")}
        </Link>
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">{workOrder.title}</h1>
        <span
          className={`rounded px-2 py-0.5 text-sm ${statusClass(workOrder.status)}`}
          data-testid="work-order-status"
        >
          {t(`statuses.${workOrder.status}`)}
        </span>
      </div>
      <p className="text-slate-700">
        {workOrder.customerName}
        {workOrder.address ? ` – ${workOrder.address}` : ""}
      </p>
      <WorkOrdersNav current={null} canEdit={canEdit} />
      <WorkOrderForm customers={customers} staff={staff} templates={templates} initial={loaded} canEdit={canEdit} />
    </div>
  );
}
