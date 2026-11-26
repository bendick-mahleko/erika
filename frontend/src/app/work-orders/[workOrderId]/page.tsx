import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getFormatter, getTranslations } from "next-intl/server";
import {
  fetchChecklistTemplates,
  fetchCustomers,
  fetchSession,
  fetchStaff,
  fetchSyncConflicts,
  fetchWorkOrder,
  fetchWorkOrderReport,
} from "@/lib/api/client";
import { statusClass } from "@/lib/workorders";
import { WorkOrdersNav } from "../WorkOrdersNav";
import { WorkOrderForm } from "../WorkOrderForm";
import { ReportSection } from "./ReportSection";

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
  const [loaded, customers, staff, templates, orderConflicts, report] = await Promise.all([
    fetchWorkOrder(workOrderId),
    fetchCustomers(true),
    fetchStaff(),
    fetchChecklistTemplates(true),
    fetchSyncConflicts(workOrderId),
    fetchWorkOrderReport(workOrderId),
  ]);
  if (!loaded) notFound();
  // Notes on the report itself, e.g. two technicians wrote the work description at the same time.
  const reportConflicts = report?.report ? await fetchSyncConflicts(report.report.id) : [];
  const conflicts = [...orderConflicts, ...reportConflicts].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const t = await getTranslations("WorkOrders");
  const canEdit = session.role === "OWNER" || session.role === "MEMBER";
  const { workOrder } = loaded;
  const format = await getFormatter();
  /** A conflict value is JSON; statuses get their German name. */
  const shown = (field: string, json?: string) => {
    if (json === undefined || json === "null") return "–";
    const value: unknown = JSON.parse(json);
    if (field === "status" && typeof value === "string") return t(`statuses.${value}` as "statuses.PLANNED");
    return Array.isArray(value) ? value.join(", ") : String(value);
  };
  const fieldName = (field: string) =>
    t.has(`syncFields.${field}` as "syncFields.status") ? t(`syncFields.${field}` as "syncFields.status") : field;
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
      {(workOrder.workNotes || conflicts.length > 0) && (
        <section aria-labelledby="from-app" className="space-y-3 rounded border border-slate-300 p-4">
          <h2 id="from-app" className="text-lg font-semibold">
            {t("fromApp")}
          </h2>
          {workOrder.workNotes && (
            <div>
              <h3 className="font-medium">{t("workNotes")}</h3>
              <p className="whitespace-pre-line text-slate-800" data-testid="work-notes">
                {workOrder.workNotes}
              </p>
            </div>
          )}
          {conflicts.length > 0 && (
            <div>
              <h3 className="font-medium">{t("conflictsTitle")}</h3>
              <p className="text-sm text-slate-700">{t("conflictsIntro")}</p>
              <ul className="mt-2 divide-y divide-slate-200" data-testid="sync-conflicts">
                {conflicts.map((c) => (
                  <li key={c.id} className="py-2">
                    {t(c.winner === "DEVICE" ? "conflictDeviceWon" : "conflictServerWon", {
                      field: fieldName(c.field),
                      app: shown(c.field, c.deviceValue),
                      office: shown(c.field, c.serverValue),
                      person: c.displayName,
                    })}
                    <span className="ml-2 text-sm text-slate-600">
                      {format.dateTime(new Date(c.createdAt), { dateStyle: "medium", timeStyle: "short" })}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}
      {report && <ReportSection report={report} />}
    </div>
  );
}
