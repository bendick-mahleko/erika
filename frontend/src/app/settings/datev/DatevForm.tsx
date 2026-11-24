"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode, type DatevSettings } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/** Consultant and client number, chart of accounts and accounts for the DATEV Buchungsstapel (owner only). */
export function DatevForm({ initial, etag, canEdit }: { initial: DatevSettings; etag: string; canEdit: boolean }) {
  const t = useTranslations("Datev");
  const te = useErrorText();
  const [settings, setSettings] = useState(initial);
  const [version, setVersion] = useState(etag);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim() || undefined;
    const number = (name: string) => (text(name) ? Number(text(name)) : undefined);
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const {
        data,
        error: problem,
        response,
      } = await browserApi.PUT("/datev-settings", {
        params: { header: { "If-Match": version } },
        body: {
          consultantNumber: number("consultantNumber"),
          clientNumber: number("clientNumber"),
          chart: (text("chart") ?? "SKR03") as DatevSettings["chart"],
          fiscalYearStartMonth: number("fiscalYearStartMonth") ?? 1,
          accountLength: number("accountLength") ?? 4,
          expenseAccount: text("expenseAccount"),
          revenueAccount: text("revenueAccount"),
          creditorAccount: text("creditorAccount"),
          debtorAccount: text("debtorAccount"),
        },
      });
      if (data) {
        setSettings(data);
        setVersion(response.headers.get("ETag") ?? version);
        setSaved(true);
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const s = settings;
  return (
    <form method="post" onSubmit={submit} className="max-w-xl space-y-4">
      {!canEdit && <p className="text-slate-700">{t("readOnly")}</p>}
      <ErrorAlert message={error} />
      <fieldset disabled={!canEdit} className="space-y-4">
        <Field
          id="consultantNumber"
          label={t("consultantNumber")}
          inputMode="numeric"
          pattern="[0-9]{4,7}"
          defaultValue={s.consultantNumber ?? ""}
        />
        <Field
          id="clientNumber"
          label={t("clientNumber")}
          inputMode="numeric"
          pattern="[0-9]{1,5}"
          defaultValue={s.clientNumber ?? ""}
        />
        <div className="space-y-1">
          <label htmlFor="chart" className="block font-medium">
            {t("chart")}
          </label>
          <select
            id="chart"
            name="chart"
            defaultValue={s.chart}
            className="block w-full rounded border border-slate-400 px-3 py-2"
          >
            <option value="SKR03">SKR 03</option>
            <option value="SKR04">SKR 04</option>
          </select>
        </div>
        <Field
          id="fiscalYearStartMonth"
          label={t("fiscalYearStartMonth")}
          type="number"
          min={1}
          max={12}
          required
          defaultValue={s.fiscalYearStartMonth}
        />
        <Field
          id="accountLength"
          label={t("accountLength")}
          type="number"
          min={4}
          max={8}
          required
          defaultValue={s.accountLength}
        />
        <fieldset className="space-y-4 rounded border border-slate-200 p-4">
          <legend className="px-1 font-medium">{t("accounts")}</legend>
          {(["expenseAccount", "revenueAccount", "creditorAccount", "debtorAccount"] as const).map((key) => (
            <Field
              key={key}
              id={key}
              label={t(key)}
              inputMode="numeric"
              pattern="[0-9]{4,9}"
              hint={t("current", { value: s[key] })}
            />
          ))}
        </fieldset>
        {canEdit && <SubmitButton busy={busy}>{t("save")}</SubmitButton>}
      </fieldset>
      <p role="status" className="text-green-800">
        {saved ? t("saved") : ""}
      </p>
    </form>
  );
}
