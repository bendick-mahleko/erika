"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Export, type ExportScope } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const SCOPES: ExportScope[] = ["ALL", "IN", "OUT"];

/** Period export (every role) and full account export (owners); the list refreshes while exports are built. */
export function ExportsManager({
  initial,
  isOwner,
  highlight,
  defaultFrom,
  defaultTo,
}: {
  initial: Export[];
  isOwner: boolean;
  highlight?: string;
  defaultFrom: string;
  defaultTo: string;
}) {
  const t = useTranslations("Exports");
  const te = useErrorText();
  const format = useFormatter();
  const [exports, setExports] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const building = exports.some((e) => e.state === "QUEUED");

  useEffect(() => {
    if (!building) return;
    const timer = setInterval(async () => {
      const { data } = await browserApi.GET("/exports").catch(() => ({ data: undefined }));
      if (data) setExports(data.items);
    }, 3000);
    return () => clearInterval(timer);
  }, [building]);

  async function start(body: { kind: "PERIOD" | "FULL"; from?: string; to?: string; scope?: ExportScope }) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { data, error: problem } = await browserApi.POST("/exports", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body,
      });
      if (data) {
        setExports((all) => [data, ...all.filter((e) => e.id !== data.id)]);
        setNotice(t("started"));
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  function submitPeriod(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    void start({
      kind: "PERIOD",
      from: String(form.get("from")),
      to: String(form.get("to")),
      scope: String(form.get("scope")) as ExportScope,
    });
  }

  const label = (e: Export) =>
    e.kind === "PERIOD"
      ? t("kinds.PERIOD", {
          from: format.dateTime(new Date(`${e.from}T00:00:00`), { dateStyle: "medium" }),
          to: format.dateTime(new Date(`${e.to}T00:00:00`), { dateStyle: "medium" }),
        })
      : t("kinds.FULL");

  return (
    <div className="space-y-8">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {notice}
      </p>

      <section aria-labelledby="period-title" className="space-y-3">
        <h2 id="period-title" className="text-xl font-semibold">
          {t("period")}
        </h2>
        <form onSubmit={submitPeriod} className="grid max-w-3xl gap-4 sm:grid-cols-3">
          <Field id="from" label={t("from")} type="date" required defaultValue={defaultFrom} />
          <Field id="to" label={t("to")} type="date" required defaultValue={defaultTo} />
          <div className="space-y-1">
            <label htmlFor="scope" className="block font-medium">
              {t("scope")}
            </label>
            <select
              id="scope"
              name="scope"
              defaultValue="ALL"
              className="block w-full rounded border border-slate-400 px-3 py-2"
            >
              {SCOPES.map((s) => (
                <option key={s} value={s}>
                  {t(`scopes.${s}`)}
                </option>
              ))}
            </select>
          </div>
          <div>
            <SubmitButton busy={busy}>{t("start")}</SubmitButton>
          </div>
        </form>
      </section>

      {isOwner && (
        <section aria-labelledby="full-title" className="space-y-3">
          <h2 id="full-title" className="text-xl font-semibold">
            {t("full")}
          </h2>
          <p className="max-w-3xl text-slate-700">{t("fullIntro")}</p>
          <button
            type="button"
            disabled={busy}
            onClick={() => start({ kind: "FULL" })}
            data-testid="full-export"
            className="rounded border border-brand-700 px-4 py-2 font-medium text-brand-700 hover:bg-slate-50 disabled:opacity-60"
          >
            {t("fullStart")}
          </button>
        </section>
      )}

      <section aria-labelledby="list-title" className="space-y-3">
        <h2 id="list-title" className="text-xl font-semibold">
          {t("list")}
        </h2>
        {exports.length === 0 ? (
          <p className="text-slate-700">{t("empty")}</p>
        ) : (
          <ul className="space-y-2" data-testid="export-list">
            {exports.map((e) => (
              <li
                key={e.id}
                data-testid={`export-${e.id}`}
                className={`flex flex-wrap items-center gap-x-4 gap-y-1 rounded border px-3 py-2 ${
                  e.id === highlight ? "border-brand-700 bg-slate-50" : "border-slate-200"
                }`}
              >
                <span className="font-medium">{label(e)}</span>
                {e.kind === "PERIOD" && <span className="text-slate-700">{t(`scopes.${e.scope}`)}</span>}
                <span data-testid="export-state">{t(`states.${e.state}`)}</span>
                {e.state === "READY" && e.documentCount != null && (
                  <span className="text-slate-700">{t("documents", { count: e.documentCount })}</span>
                )}
                {e.state === "READY" && e.expiresAt && (
                  <span className="text-slate-700">
                    {t("expires", {
                      date: format.dateTime(new Date(e.expiresAt), { dateStyle: "medium", timeStyle: "short" }),
                    })}
                  </span>
                )}
                {e.requestedByName && (
                  <span className="text-slate-700">{t("requestedBy", { name: e.requestedByName })}</span>
                )}
                {e.state === "READY" && (
                  <a
                    href={`/api/v1/exports/${e.id}/download`}
                    aria-label={t("downloadLabel", { name: label(e) })}
                    className="ml-auto rounded bg-brand-700 px-3 py-1 font-medium text-white hover:bg-brand-900"
                  >
                    {t("download")}
                  </a>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
