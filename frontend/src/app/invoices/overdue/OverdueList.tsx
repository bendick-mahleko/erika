"use client";

import { useState } from "react";
import Link from "next/link";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type OverdueInvoice } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

const MAX_REMINDERS = 3;

/** M-IV-06: overdue invoices, oldest due first, with the reminders sent so far. */
export function OverdueList({ initial, canEdit }: { initial: OverdueInvoice[]; canEdit: boolean }) {
  const t = useTranslations("Overdue");
  const te = useErrorText();
  const format = useFormatter();
  const [items, setItems] = useState(initial);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function remind(inv: OverdueInvoice) {
    const level = inv.reminders.length + 1;
    const fallback = inv.buyerEmail ?? "";
    const to = window.prompt(t(level === 1 ? "promptReminder" : "promptDunning", { number: inv.number }), fallback);
    if (to === null) return;
    setBusy(inv.invoiceId);
    setError(null);
    setStatus("");
    try {
      const { data, error: problem } = await browserApi.POST("/invoices/{invoiceId}/reminders", {
        params: { path: { invoiceId: inv.invoiceId }, header: { "Idempotency-Key": crypto.randomUUID() } },
        body: to.trim() ? { to: to.trim() } : {},
      });
      if (data) {
        setItems((list) =>
          list.map((x) =>
            x.invoiceId === inv.invoiceId
              ? {
                  ...x,
                  reminders: [
                    ...x.reminders,
                    { level: data.level, recipient: to.trim() || fallback, sentAt: new Date().toISOString() },
                  ],
                }
              : x,
          ),
        );
        setStatus(t("sent", { number: inv.number, level: data.level }));
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(null);
    }
  }

  const date = (d: string) => format.dateTime(new Date(`${d.slice(0, 10)}T00:00:00`), { dateStyle: "medium" });
  if (items.length === 0) return <p className="text-slate-700">{t("empty")}</p>;

  return (
    <div className="space-y-3">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {status}
      </p>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[48rem] text-left text-sm" data-testid="overdue-list">
          <thead className="border-b border-slate-300">
            <tr>
              <th scope="col" className="py-2 pr-3">
                {t("number")}
              </th>
              <th scope="col" className="py-2 pr-3">
                {t("buyer")}
              </th>
              <th scope="col" className="py-2 pr-3">
                {t("due")}
              </th>
              <th scope="col" className="py-2 pr-3 text-right">
                {t("gross")}
              </th>
              <th scope="col" className="py-2 pr-3">
                {t("reminders")}
              </th>
              <th scope="col" className="py-2">
                <span className="sr-only">{t("actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((inv) => {
              const level = inv.reminders.length + 1;
              return (
                <tr key={inv.invoiceId} className="border-b border-slate-100 align-top">
                  <td className="py-2 pr-3">
                    <Link href={`/invoices/${inv.invoiceId}`} className="text-brand-700 underline">
                      {inv.number}
                    </Link>
                  </td>
                  <td className="py-2 pr-3">{inv.buyerName}</td>
                  <td className="py-2 pr-3">
                    {date(inv.dueDate)}
                    <span className="block text-xs text-red-800">{t("daysOverdue", { days: inv.daysOverdue })}</span>
                  </td>
                  <td className="py-2 pr-3 text-right tabular-nums">
                    {format.number(Number(inv.gross), { style: "currency", currency: "EUR" })}
                  </td>
                  <td className="py-2 pr-3">
                    {inv.reminders.length === 0
                      ? t("none")
                      : inv.reminders.map((r) => (
                          <span key={r.level} className="block">
                            {t(r.level === 1 ? "reminderSent" : "dunningSent", {
                              level: r.level - 1,
                              date: date(r.sentAt),
                            })}
                          </span>
                        ))}
                  </td>
                  <td className="py-2">
                    {canEdit && level <= MAX_REMINDERS && (
                      <button
                        type="button"
                        onClick={() => remind(inv)}
                        disabled={busy !== null}
                        className="rounded border px-3 py-1 text-sm disabled:opacity-60"
                      >
                        {level === 1 ? t("sendReminder") : t("sendDunning", { level: level - 1 })}
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
