import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getFormatter, getTranslations } from "next-intl/server";
import { fetchSession, fetchStaff, fetchWorkOrders, type WorkOrderStatus } from "@/lib/api/client";
import { STATUSES, statusClass } from "@/lib/workorders";
import { WorkOrdersNav } from "./WorkOrdersNav";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("WorkOrders");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Work orders (MARA M-OF-02): unplanned first, then by date; filter by status. */
export default async function WorkOrdersPage({ searchParams }: { searchParams: Promise<{ status?: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const { status: raw } = await searchParams;
  const status = STATUSES.includes(raw as WorkOrderStatus) ? (raw as WorkOrderStatus) : undefined;
  const [orders, staff] = await Promise.all([fetchWorkOrders(status), fetchStaff()]);
  const names = new Map(staff.map((s) => [s.userId, s.displayName]));
  const t = await getTranslations("WorkOrders");
  const format = await getFormatter();
  const canEdit = session.role === "OWNER" || session.role === "MEMBER";

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      <WorkOrdersNav current="list" canEdit={canEdit} />
      <form method="get" className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <label htmlFor="status" className="block font-medium">
            {t("filterStatus")}
          </label>
          <select
            id="status"
            name="status"
            defaultValue={status ?? ""}
            className="rounded border border-slate-400 px-3 py-2"
          >
            <option value="">{t("allStatuses")}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`statuses.${s}`)}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="rounded border border-brand-700 px-4 py-2 text-brand-700 hover:bg-brand-50">
          {t("filter")}
        </button>
      </form>
      {orders.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm" data-testid="work-order-list">
            <thead className="border-b border-slate-300">
              <tr>
                <th scope="col" className="py-2 pr-4">
                  {t("when")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("workTitle")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("customer")}
                </th>
                <th scope="col" className="py-2 pr-4">
                  {t("assignees")}
                </th>
                <th scope="col" className="py-2">
                  {t("status")}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {orders.map((o) => (
                <tr key={o.id}>
                  <td className="py-2 pr-4 whitespace-nowrap">
                    {o.plannedFrom
                      ? format.dateTime(new Date(o.plannedFrom), { dateStyle: "medium", timeStyle: "short" })
                      : t("unplanned")}
                  </td>
                  <td className="py-2 pr-4">
                    <Link
                      href={`/work-orders/${o.id}`}
                      className="font-medium text-brand-700 underline underline-offset-4"
                    >
                      {o.title}
                    </Link>
                    {o.address && <span className="block text-slate-600">{o.address}</span>}
                  </td>
                  <td className="py-2 pr-4">{o.customerName}</td>
                  <td className="py-2 pr-4">
                    {o.assigneeIds.length === 0
                      ? t("nobody")
                      : o.assigneeIds.map((id) => names.get(id) ?? "?").join(", ")}
                  </td>
                  <td className="py-2">
                    <span className={`rounded px-2 py-0.5 ${statusClass(o.status)}`}>{t(`statuses.${o.status}`)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
