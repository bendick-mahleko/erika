"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Article, type ArticleInput } from "@/lib/api/browser";
import { normaliseDecimal, RATES, UNITS } from "@/lib/creator";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const select = "block w-full rounded border border-slate-400 px-3 py-2";

/** Articles and services with unit (UN/ECE Rec 20), price and VAT rate (CR-05). */
export function ArticlesManager({ initial, canEdit }: { initial: Article[]; canEdit: boolean }) {
  const t = useTranslations("Articles");
  const tc = useTranslations("Creator");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const [articles, setArticles] = useState(initial);
  const [editing, setEditing] = useState<Article | "new" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [query, setQuery] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim() || undefined;
    const body: ArticleInput = {
      name: text("name") ?? "",
      articleNumber: text("articleNumber"),
      description: text("description"),
      unitCode: text("unitCode") ?? "C62",
      netPrice: normaliseDecimal(text("netPrice") ?? "0"),
      vatRate: (text("vatRate") ?? "STANDARD_19") as ArticleInput["vatRate"],
      archived: form.get("archived") === "on",
    };
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      const result =
        editing === "new"
          ? await browserApi.POST("/articles", { params: { header: { "Idempotency-Key": idempotencyKey() } }, body })
          : await browserApi.PUT("/articles/{articleId}", {
              params: {
                path: { articleId: (editing as Article).id },
                header: { "If-Match": `"${(editing as Article).version}"` },
              },
              body,
            });
      if (result.data) {
        const saved = result.data;
        setArticles((list) =>
          [...list.filter((a) => a.id !== saved.id), saved].sort((a, b) => a.name.localeCompare(b.name, "de")),
        );
        setEditing(null);
        setMessage(t("saved"));
        router.refresh();
      } else {
        setError(te(problemCode(result.error)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const current = editing && editing !== "new" ? editing : undefined;
  const visible = articles.filter((a) =>
    [a.name, a.articleNumber].some((v) => v?.toLowerCase().includes(query.trim().toLowerCase())),
  );

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      <div className="flex flex-wrap items-end gap-4">
        <div className="w-72">
          <Field id="article-search" label={t("search")} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {canEdit && editing === null && (
          <button
            type="button"
            className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
            onClick={() => setEditing("new")}
            data-testid="new-article"
          >
            {t("new")}
          </button>
        )}
      </div>

      {editing !== null && (
        <form
          onSubmit={submit}
          className="max-w-2xl space-y-4 rounded border border-slate-300 p-4"
          key={current?.id ?? "new"}
        >
          <fieldset disabled={busy} className="grid gap-4 sm:grid-cols-2">
            <legend className="mb-2 font-semibold">{current ? current.name : t("new")}</legend>
            <Field id="name" label={t("name")} required maxLength={200} defaultValue={current?.name} />
            <Field id="articleNumber" label={t("articleNumber")} maxLength={50} defaultValue={current?.articleNumber} />
            <div className="sm:col-span-2">
              <Field id="description" label={t("description")} maxLength={1000} defaultValue={current?.description} />
            </div>
            <div className="space-y-1">
              <label htmlFor="unitCode" className="block font-medium">
                {t("unitCode")}
              </label>
              <select id="unitCode" name="unitCode" className={select} defaultValue={current?.unitCode ?? "C62"}>
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {tc(`unit.${u}`)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              id="netPrice"
              label={t("netPrice")}
              required
              inputMode="decimal"
              pattern="\d{1,12}([.,]\d{1,4})?"
              defaultValue={current?.netPrice}
            />
            <div className="space-y-1">
              <label htmlFor="vatRate" className="block font-medium">
                {t("vatRate")}
              </label>
              <select id="vatRate" name="vatRate" className={select} defaultValue={current?.vatRate ?? "STANDARD_19"}>
                {RATES.map((r) => (
                  <option key={r} value={r}>
                    {tc(`vatRate.${r}`)}
                  </option>
                ))}
              </select>
            </div>
            {current && (
              <div className="flex items-center gap-2 sm:col-span-2">
                <input id="archived" name="archived" type="checkbox" defaultChecked={current.archived} />
                <label htmlFor="archived">{t("archive")}</label>
              </div>
            )}
          </fieldset>
          <div className="flex gap-4">
            <SubmitButton busy={busy}>{t("save")}</SubmitButton>
            <button type="button" className="underline" onClick={() => setEditing(null)}>
              {t("cancel")}
            </button>
          </div>
        </form>
      )}

      {visible.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <ul className="divide-y divide-slate-200" data-testid="article-list">
          {visible.map((a) => (
            <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
              <span>
                <span className="font-medium">{a.name}</span>
                <span className="ml-2 tabular-nums text-slate-600">
                  {format.number(Number(a.netPrice), { style: "currency", currency: "EUR" })} /{" "}
                  {tc.has(`unit.${a.unitCode}`) ? tc(`unit.${a.unitCode}`) : a.unitCode} · {tc(`vatRate.${a.vatRate}`)}
                </span>
                {a.archived && <span className="ml-2 text-sm text-slate-500">({t("archived")})</span>}
              </span>
              {canEdit && (
                <button type="button" className="text-brand-700 underline" onClick={() => setEditing(a)}>
                  {t("edit")}
                  <span className="sr-only">: {a.name}</span>
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
