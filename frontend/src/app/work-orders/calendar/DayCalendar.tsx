"use client";

import { useCallback, useEffect, useState, type DragEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type StaffMember, type WorkOrder } from "@/lib/api/browser";
import { dayRange, isoDay, LOCKED, statusClass } from "@/lib/workorders";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

const UNASSIGNED = "unassigned";

/**
 * Day view by person (M-OF-03). Dragging a work order to another column hands it from that
 * column's person to the other one; the "move to" menu under each work order does the same
 * without a mouse (WCAG 2.1.1).
 */
export function DayCalendar({
  day: initialDay,
  staff,
  canEdit,
}: {
  day: string;
  staff: StaffMember[];
  canEdit: boolean;
}) {
  const t = useTranslations("WorkOrders");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const [day, setDay] = useState(initialDay);
  const [orders, setOrders] = useState<WorkOrder[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  const fetchDay = useCallback(async (d: string) => {
    const { from, to } = dayRange(d);
    return browserApi.GET("/work-orders", {
      params: { query: { from: from.toISOString(), to: to.toISOString(), limit: 500 } },
    });
  }, []);

  /** Reloads the day, e.g. after a failed move showed outdated data. */
  async function reload() {
    const { data, error: problem } = await fetchDay(day);
    if (data) setOrders(data.items);
    else setError(te(problemCode(problem)));
  }

  useEffect(() => {
    let cancelled = false;
    fetchDay(day).then(({ data, error: problem }) => {
      if (cancelled) return;
      if (data) setOrders(data.items);
      else setError(te(problemCode(problem)));
    });
    return () => {
      cancelled = true;
    };
  }, [day, fetchDay, te]);

  function go(offset: number | "today") {
    const next =
      offset === "today"
        ? isoDay(new Date())
        : isoDay(new Date(dayRange(day).from.getTime() + offset * 86_400_000 + 3_600_000));
    setDay(next);
    setOrders(null);
    router.replace(`/work-orders/calendar?day=${next}`, { scroll: false });
  }

  const name = (userId: string | null) =>
    userId === null ? t("unassigned") : (staff.find((s) => s.userId === userId)?.displayName ?? "?");

  async function move(order: WorkOrder, from: string | null, to: string | null) {
    if (from === to) return;
    const assigneeIds = [...order.assigneeIds.filter((id) => id !== from)];
    if (to !== null && !assigneeIds.includes(to)) assigneeIds.push(to);
    setError(null);
    setMessage("");
    const { data, error: problem } = await browserApi.PUT("/work-orders/{workOrderId}", {
      params: { path: { workOrderId: order.id }, header: { "If-Match": `"${order.version}"` } },
      body: {
        customerId: order.customerId,
        siteId: order.siteId,
        title: order.title,
        description: order.description,
        plannedFrom: order.plannedFrom,
        plannedTo: order.plannedTo,
        assigneeIds,
        checklistTemplateId: order.checklistTemplateId,
        status: order.status,
      },
    });
    if (data) {
      setOrders((list) => list?.map((o) => (o.id === data.id ? data : o)) ?? null);
      setMessage(t("moved", { title: data.title, name: name(to) }));
    } else {
      setError(te(problemCode(problem)));
      void reload();
    }
  }

  function onDrop(event: DragEvent<HTMLElement>, to: string | null) {
    event.preventDefault();
    try {
      const { id, from } = JSON.parse(event.dataTransfer.getData("text/plain")) as { id: string; from: string | null };
      const order = orders?.find((o) => o.id === id);
      if (order) void move(order, from, to);
    } catch {
      // Not one of our cards.
    }
  }

  const columns: { key: string; userId: string | null; label: string }[] = [
    ...staff.map((s) => ({ key: s.userId, userId: s.userId as string | null, label: s.displayName })),
    { key: UNASSIGNED, userId: null, label: t("unassigned") },
  ];
  const time = (iso?: string) => (iso ? format.dateTime(new Date(iso), { timeStyle: "short" }) : "");
  const heading = day ? format.dateTime(dayRange(day).from, { dateStyle: "full" }) : "";

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className="rounded border border-slate-400 px-3 py-2" onClick={() => go(-1)}>
          {t("previousDay")}
        </button>
        <button type="button" className="rounded border border-slate-400 px-3 py-2" onClick={() => go("today")}>
          {t("today")}
        </button>
        <button type="button" className="rounded border border-slate-400 px-3 py-2" onClick={() => go(1)}>
          {t("nextDay")}
        </button>
        <h2 className="text-lg font-semibold" data-testid="calendar-day">
          {heading}
        </h2>
      </div>
      {canEdit && <p className="text-sm text-slate-700">{t("dragHint")}</p>}
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      {orders === null ? (
        <p className="text-slate-700">{t("loading")}</p>
      ) : (
        <>
          {orders.length === 0 && <p className="text-slate-700">{t("noOrdersDay")}</p>}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="calendar">
            {columns.map((col) => {
              const mine = orders.filter((o) =>
                col.userId === null ? o.assigneeIds.length === 0 : o.assigneeIds.includes(col.userId),
              );
              return (
                <section
                  key={col.key}
                  aria-label={col.label}
                  data-testid={`column-${col.key}`}
                  className="min-h-32 rounded border border-slate-300 bg-slate-50 p-3"
                  onDragOver={(e) => canEdit && e.preventDefault()}
                  onDrop={(e) => canEdit && onDrop(e, col.userId)}
                >
                  <h3 className="mb-2 font-semibold">{col.label}</h3>
                  <ul className="space-y-2">
                    {mine.map((o) => {
                      const movable = canEdit && !LOCKED.includes(o.status);
                      return (
                        <li
                          key={o.id}
                          draggable={movable}
                          onDragStart={(e) =>
                            e.dataTransfer.setData("text/plain", JSON.stringify({ id: o.id, from: col.userId }))
                          }
                          className={`rounded border border-slate-300 bg-white p-2 ${movable ? "cursor-grab" : ""}`}
                          data-testid="calendar-card"
                        >
                          <p className="text-sm text-slate-600">
                            {time(o.plannedFrom)}
                            {o.plannedTo ? ` – ${time(o.plannedTo)}` : ""}
                          </p>
                          <Link href={`/work-orders/${o.id}`} className="font-medium text-brand-700 underline">
                            {o.title}
                          </Link>
                          <p className="text-sm">{o.customerName}</p>
                          {o.address && <p className="text-sm text-slate-600">{o.address}</p>}
                          <span className={`mt-1 inline-block rounded px-2 py-0.5 text-xs ${statusClass(o.status)}`}>
                            {t(`statuses.${o.status}`)}
                          </span>
                          {movable && (
                            <div className="mt-2">
                              <label className="sr-only" htmlFor={`move-${col.key}-${o.id}`}>
                                {t("moveToLabel", { title: o.title })}
                              </label>
                              <select
                                id={`move-${col.key}-${o.id}`}
                                className="w-full rounded border border-slate-300 px-2 py-1 text-sm"
                                value=""
                                onChange={(e) => {
                                  const target = e.target.value;
                                  void move(o, col.userId, target === UNASSIGNED ? null : target);
                                }}
                              >
                                <option value="">{t("moveTo")} …</option>
                                {columns
                                  .filter((c) => c.key !== col.key)
                                  .map((c) => (
                                    <option key={c.key} value={c.key}>
                                      {c.label}
                                    </option>
                                  ))}
                              </select>
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                </section>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
