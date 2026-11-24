"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type RecurringInterval } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/** CR-09: turn this invoice into a template; ERIKA then creates a draft each month or quarter. */
export function MakeRecurring({ invoiceId, defaultName }: { invoiceId: string; defaultName: string }) {
  const t = useTranslations("Recurring");
  const te = useErrorText();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState(false);
  const firstOfNextMonth = (() => {
    const d = new Date();
    return new Date(Date.UTC(d.getFullYear(), d.getMonth() + 1, 1)).toISOString().slice(0, 10);
  })();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/recurring-invoices", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: {
          sourceInvoiceId: invoiceId,
          name: String(form.get("recurringName") ?? "").trim(),
          interval: String(form.get("recurringInterval")) as RecurringInterval,
          nextRun: String(form.get("recurringNextRun")),
        },
      });
      if (data) setCreated(true);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="max-w-2xl rounded border border-slate-200 p-4">
      <summary className="cursor-pointer font-medium">{t("makeRecurring")}</summary>
      {created ? (
        <p role="status" className="mt-3 text-green-800">
          {t("created")}{" "}
          <Link href="/invoices/recurring" className="underline">
            {t("title")}
          </Link>
        </p>
      ) : (
        <form method="post" onSubmit={submit} className="mt-3 space-y-4">
          <p className="text-sm text-slate-700">{t("makeRecurringHint")}</p>
          <ErrorAlert message={error} />
          <Field id="recurringName" label={t("name")} required maxLength={200} defaultValue={defaultName} />
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1">
              <label htmlFor="recurringInterval" className="block font-medium">
                {t("column.interval")}
              </label>
              <select
                id="recurringInterval"
                name="recurringInterval"
                defaultValue="MONTHLY"
                className="block w-full rounded border border-slate-400 px-3 py-2"
              >
                {(["MONTHLY", "QUARTERLY"] as const).map((i) => (
                  <option key={i} value={i}>
                    {t(`interval.${i}`)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              id="recurringNextRun"
              label={t("firstRun")}
              hint={t("firstRunHint")}
              type="date"
              required
              defaultValue={firstOfNextMonth}
            />
          </div>
          <SubmitButton busy={busy}>{t("create")}</SubmitButton>
        </form>
      )}
    </details>
  );
}
