"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode, type ReportInvoicingSettings, type TravelMode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, SubmitButton } from "@/components/forms";

const UNITS = [1, 5, 6, 10, 15, 30, 60] as const;
const MODES: TravelMode[] = ["TIME", "FLAT", "NONE"];

/** German input to the API's decimal; empty stays empty. */
function decimal(value: string): string | undefined {
  const s = value.trim();
  if (!s) return undefined;
  return s.includes(",") ? s.replace(/\./g, "").replace(",", ".") : s;
}

export function ReportInvoicingSettingsForm({
  initial,
  canEdit,
}: {
  initial: ReportInvoicingSettings;
  canEdit: boolean;
}) {
  const t = useTranslations("ReportInvoicingSettings");
  const te = useErrorText();
  const [unit, setUnit] = useState<number>(initial.billingUnitMinutes);
  const [rate, setRate] = useState(initial.defaultHourlyRate ?? "");
  const [mode, setMode] = useState<TravelMode>(initial.travelMode);
  const [flat, setFlat] = useState(initial.travelFlatAmount ?? "");
  const [travelRate, setTravelRate] = useState(initial.travelHourlyRate ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const { data, error: problem } = await browserApi.PUT("/report-invoicing/settings", {
        body: {
          billingUnitMinutes: unit as ReportInvoicingSettings["billingUnitMinutes"],
          defaultHourlyRate: decimal(rate),
          travelMode: mode,
          travelFlatAmount: decimal(flat),
          travelHourlyRate: decimal(travelRate),
        },
      });
      if (data) setStatus(t("saved"));
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const input = "rounded border px-2 py-1 disabled:bg-slate-100";
  return (
    <form method="post" onSubmit={save} className="max-w-xl space-y-4">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {status}
      </p>
      {!canEdit && <p className="text-slate-700">{t("ownerOnly")}</p>}
      <div className="space-y-1">
        <label htmlFor="ris-unit" className="block font-medium">
          {t("unit")}
        </label>
        <select
          id="ris-unit"
          value={unit}
          disabled={!canEdit}
          onChange={(e) => setUnit(Number(e.target.value))}
          className={input}
        >
          {UNITS.map((u) => (
            <option key={u} value={u}>
              {t("unitMinutes", { minutes: u })}
            </option>
          ))}
        </select>
        <p className="text-sm text-slate-600">{t("unitHint")}</p>
      </div>
      <div className="space-y-1">
        <label htmlFor="ris-rate" className="block font-medium">
          {t("defaultRate")}
        </label>
        <input
          id="ris-rate"
          inputMode="decimal"
          value={rate}
          disabled={!canEdit}
          onChange={(e) => setRate(e.target.value)}
          className={input}
        />
        <p className="text-sm text-slate-600">{t("defaultRateHint")}</p>
      </div>
      <fieldset className="space-y-2">
        <legend className="font-medium">{t("travel")}</legend>
        {MODES.map((m) => (
          <label key={m} className="flex items-center gap-2">
            <input
              type="radio"
              name="travelMode"
              value={m}
              checked={mode === m}
              disabled={!canEdit}
              onChange={() => setMode(m)}
            />
            {t(`modes.${m}`)}
          </label>
        ))}
      </fieldset>
      {mode === "FLAT" && (
        <div className="space-y-1">
          <label htmlFor="ris-flat" className="block font-medium">
            {t("flat")}
          </label>
          <input
            id="ris-flat"
            inputMode="decimal"
            value={flat}
            disabled={!canEdit}
            onChange={(e) => setFlat(e.target.value)}
            className={input}
          />
        </div>
      )}
      {mode === "TIME" && (
        <div className="space-y-1">
          <label htmlFor="ris-travel-rate" className="block font-medium">
            {t("travelRate")}
          </label>
          <input
            id="ris-travel-rate"
            inputMode="decimal"
            value={travelRate}
            disabled={!canEdit}
            onChange={(e) => setTravelRate(e.target.value)}
            className={input}
          />
          <p className="text-sm text-slate-600">{t("travelRateHint")}</p>
        </div>
      )}
      {canEdit && <SubmitButton busy={busy}>{t("save")}</SubmitButton>}
    </form>
  );
}
