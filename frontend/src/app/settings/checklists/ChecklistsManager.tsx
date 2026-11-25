"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import {
  browserApi,
  idempotencyKey,
  problemCode,
  type ChecklistItem,
  type ChecklistItemKind,
  type ChecklistTemplate,
} from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const KINDS: ChecklistItemKind[] = ["YES_NO", "VALUE", "PHOTO", "COMMENT"];

type Row = { key: string; label: string; kind: ChecklistItemKind; required: boolean };

/** A key the server accepts (lower case, digits, - and _), unique within the template. */
function keyFor(label: string, taken: Set<string>, index: number): string {
  const base =
    label
      .toLowerCase()
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/ß/g, "ss")
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 30) || `punkt-${index + 1}`;
  let key = base;
  for (let n = 2; taken.has(key); n++) key = `${base}-${n}`.slice(0, 40);
  taken.add(key);
  return key;
}

/** Checklist templates per job type (M-OF-05). Existing items keep their key, so answers stay attached. */
export function ChecklistsManager({ initial, canEdit }: { initial: ChecklistTemplate[]; canEdit: boolean }) {
  const t = useTranslations("Checklists");
  const te = useErrorText();
  const [templates, setTemplates] = useState(initial);
  const [editing, setEditing] = useState<ChecklistTemplate | "new" | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  function open(target: ChecklistTemplate | "new") {
    setEditing(target);
    setRows(
      target === "new" ? [{ key: "", label: "", kind: "YES_NO", required: true }] : target.items.map((i) => ({ ...i })),
    );
    setMessage("");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const taken = new Set(rows.map((r) => r.key).filter(Boolean));
    const items: ChecklistItem[] = rows
      .filter((r) => r.label.trim())
      .map((r, i) => ({ ...r, label: r.label.trim(), key: r.key || keyFor(r.label, taken, i) }));
    const body = { name: String(form.get("name") ?? "").trim(), items, archived: form.get("archived") === "on" };
    setBusy(true);
    setError(null);
    const result =
      editing === "new"
        ? await browserApi.POST("/checklist-templates", {
            params: { header: { "Idempotency-Key": idempotencyKey() } },
            body,
          })
        : await browserApi.PUT("/checklist-templates/{checklistTemplateId}", {
            params: {
              path: { checklistTemplateId: (editing as ChecklistTemplate).id },
              header: { "If-Match": `"${(editing as ChecklistTemplate).version}"` },
            },
            body,
          });
    setBusy(false);
    if (result.data) {
      const saved = result.data;
      setTemplates((list) =>
        [...list.filter((x) => x.id !== saved.id), saved].sort((a, b) => a.name.localeCompare(b.name, "de")),
      );
      setEditing(null);
      setMessage(t("saved"));
    } else {
      setError(te(problemCode(result.error)));
    }
  }

  const update = (index: number, change: Partial<Row>) =>
    setRows((list) => list.map((r, i) => (i === index ? { ...r, ...change } : r)));
  const current = editing && editing !== "new" ? editing : undefined;

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      {templates.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <ul className="divide-y divide-slate-200" data-testid="checklist-list">
          {templates.map((tpl) => (
            <li key={tpl.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
              <span>
                <span className="font-medium">{tpl.name}</span>
                <span className="ml-2 text-slate-600">{t("itemCount", { count: tpl.items.length })}</span>
                {tpl.archived && <span className="ml-2 text-sm text-slate-500">({t("archived")})</span>}
              </span>
              {canEdit && editing === null && (
                <button type="button" className="text-brand-700 underline" onClick={() => open(tpl)}>
                  {t("edit")}
                  <span className="sr-only">: {tpl.name}</span>
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && editing === null && (
        <button
          type="button"
          className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
          onClick={() => open("new")}
        >
          {t("new")}
        </button>
      )}
      {editing !== null && (
        <form
          method="post"
          onSubmit={submit}
          className="max-w-3xl space-y-4 rounded border border-slate-300 p-4"
          key={current?.id ?? "new"}
        >
          <fieldset disabled={busy} className="space-y-4">
            <legend className="mb-2 font-semibold">{current ? current.name : t("new")}</legend>
            <Field id="name" label={t("name")} required maxLength={200} defaultValue={current?.name} />
            <fieldset className="space-y-3">
              <legend className="font-medium">{t("items")}</legend>
              <ol className="space-y-3">
                {rows.map((row, i) => (
                  <li
                    key={i}
                    className="grid gap-2 rounded border border-slate-200 p-2 sm:grid-cols-[1fr_auto_auto_auto] sm:items-end"
                  >
                    <div className="space-y-1">
                      <label htmlFor={`item-label-${i}`} className="block text-sm font-medium">
                        {t("itemLabel", { n: i + 1 })}
                      </label>
                      <input
                        id={`item-label-${i}`}
                        className="block w-full rounded border border-slate-400 px-3 py-2"
                        maxLength={200}
                        value={row.label}
                        onChange={(e) => update(i, { label: e.target.value })}
                      />
                    </div>
                    <div className="space-y-1">
                      <label htmlFor={`item-kind-${i}`} className="block text-sm font-medium">
                        {t("itemKind", { n: i + 1 })}
                      </label>
                      <select
                        id={`item-kind-${i}`}
                        className="rounded border border-slate-400 px-2 py-2"
                        value={row.kind}
                        onChange={(e) => update(i, { kind: e.target.value as ChecklistItemKind })}
                      >
                        {KINDS.map((k) => (
                          <option key={k} value={k}>
                            {t(`kinds.${k}`)}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="flex items-center gap-2 pb-2">
                      <input
                        id={`item-required-${i}`}
                        type="checkbox"
                        className="h-5 w-5"
                        checked={row.required}
                        onChange={(e) => update(i, { required: e.target.checked })}
                      />
                      <label htmlFor={`item-required-${i}`}>
                        {t("itemRequired")}
                        <span className="sr-only">: {t("item", { n: i + 1 })}</span>
                      </label>
                    </div>
                    <button
                      type="button"
                      className="pb-2 text-left text-red-800 underline"
                      onClick={() => setRows((list) => list.filter((_, j) => j !== i))}
                      disabled={rows.length <= 1}
                    >
                      {t("removeItem", { n: i + 1 })}
                    </button>
                  </li>
                ))}
              </ol>
              <button
                type="button"
                className="rounded border border-brand-700 px-3 py-1 text-brand-700"
                onClick={() => setRows((list) => [...list, { key: "", label: "", kind: "YES_NO", required: false }])}
              >
                {t("addItem")}
              </button>
            </fieldset>
            {current && (
              <div className="flex items-center gap-2">
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
    </div>
  );
}
