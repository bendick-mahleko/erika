"use client";

import { useState } from "react";
import Link from "next/link";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type RecurringInvoice } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/** CR-09: the recurring invoices with their next run; pause, resume or delete them. */
export function RecurringManager({ initial, canEdit }: { initial: RecurringInvoice[]; canEdit: boolean }) {
  const t = useTranslations("Recurring");
  const te = useErrorText();
  const format = useFormatter();
  const [items, setItems] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function setActive(r: RecurringInvoice, active: boolean) {
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const { data, error: problem } = await browserApi.PUT("/recurring-invoices/{recurringId}", {
        params: { path: { recurringId: r.id }, header: { "If-Match": `"${r.version}"` } },
        body: { name: r.name, interval: r.interval, nextRun: r.nextRun, active },
      });
      if (data) {
        setItems((list) => list.map((x) => (x.id === data.id ? data : x)));
        setStatus(t(active ? "resumed" : "paused", { name: data.name }));
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(r: RecurringInvoice) {
    if (!window.confirm(t("deleteConfirm", { name: r.name }))) return;
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const { error: problem, response } = await browserApi.DELETE("/recurring-invoices/{recurringId}", {
        params: { path: { recurringId: r.id } },
      });
      if (response.ok) {
        setItems((list) => list.filter((x) => x.id !== r.id));
        setStatus(t("deleted", { name: r.name }));
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const date = (d: string) => format.dateTime(new Date(`${d}T00:00:00`), { dateStyle: "medium" });
  const button = "rounded border px-3 py-1 text-sm disabled:opacity-60";

  return (
    <div className="space-y-3">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {status}
      </p>
      {items.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[40rem] text-left text-sm" data-testid="recurring-list">
            <thead className="border-b border-slate-300">
              <tr>
                <th scope="col" className="py-2 pr-3">
                  {t("column.name")}
                </th>
                <th scope="col" className="py-2 pr-3">
                  {t("column.customer")}
                </th>
                <th scope="col" className="py-2 pr-3">
                  {t("column.interval")}
                </th>
                <th scope="col" className="py-2 pr-3">
                  {t("column.nextRun")}
                </th>
                <th scope="col" className="py-2 pr-3">
                  {t("column.lastDraft")}
                </th>
                {canEdit && (
                  <th scope="col" className="py-2">
                    <span className="sr-only">{t("column.actions")}</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {items.map((r) => (
                <tr key={r.id} className="border-b border-slate-200">
                  <td className="py-2 pr-3 font-medium">{r.name}</td>
                  <td className="py-2 pr-3">{r.buyerName}</td>
                  <td className="py-2 pr-3">{t(`interval.${r.interval}`)}</td>
                  <td className="py-2 pr-3">{r.active ? date(r.nextRun) : t("pausedLabel")}</td>
                  <td className="py-2 pr-3">
                    {r.lastDraftId && r.lastRun ? (
                      <Link href={`/invoices/${r.lastDraftId}`} className="text-brand-700 underline">
                        {date(r.lastRun)}
                      </Link>
                    ) : (
                      "–"
                    )}
                  </td>
                  {canEdit && (
                    <td className="flex flex-wrap gap-2 py-2">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => setActive(r, !r.active)}
                        className={`${button} border-brand-700 text-brand-700`}
                        aria-label={t(r.active ? "pauseLabel" : "resumeLabel", { name: r.name })}
                      >
                        {t(r.active ? "pause" : "resume")}
                      </button>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => remove(r)}
                        className={`${button} border-red-700 text-red-800`}
                        aria-label={t("deleteLabel", { name: r.name })}
                      >
                        {t("delete")}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
