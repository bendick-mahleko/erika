"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import {
  browserApi,
  idempotencyKey,
  problemCode,
  type Article,
  type Customer,
  type DraftProblem,
  type Invoice,
  type InvoiceContent,
  type InvoiceLineInput,
  type InvoiceSettings,
} from "@/lib/api/browser";
import {
  clientProblems,
  FORMATS,
  isXRechnung,
  normaliseDecimal,
  problemFor,
  RATES,
  splitField,
  TREATMENTS,
  UNITS,
} from "@/lib/creator";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

type SaveState = "idle" | "dirty" | "saving" | "saved" | "error" | "conflict";

const input = "block w-full rounded border border-slate-400 px-3 py-2 aria-[invalid=true]:border-red-700";

/** A labelled input with hint and inline problem (WCAG 1.3.1, 3.3.1). */
function Labeled({
  id,
  label,
  hint,
  problem,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  problem?: string;
  children: (props: { id: string; "aria-invalid": boolean; "aria-describedby"?: string }) => ReactNode;
}) {
  const described =
    [hint ? `${id}-hint` : null, problem ? `${id}-problem` : null].filter(Boolean).join(" ") || undefined;
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block font-medium">
        {label}
      </label>
      {children({ id, "aria-invalid": Boolean(problem), "aria-describedby": described })}
      {hint && (
        <p id={`${id}-hint`} className="text-sm text-slate-600">
          {hint}
        </p>
      )}
      {problem && (
        <p id={`${id}-problem`} className="text-sm text-red-800">
          {problem}
        </p>
      )}
    </div>
  );
}

/**
 * The draft form (CR-01, CR-05): saved as it is edited (If-Match), filled from saved customers
 * and articles, checked as you type with the same rules as the server. "Issue" is offered only
 * when nothing is missing; the server checks again and validates the generated file.
 */
export function InvoiceEditor({
  initial,
  etag: initialEtag,
  settings,
  customers,
  articles,
  canEdit,
}: {
  initial: Invoice;
  etag: string;
  settings: InvoiceSettings | null;
  customers: Customer[];
  articles: Article[];
  canEdit: boolean;
}) {
  const t = useTranslations("Editor");
  const tc = useTranslations("Creator");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();

  const [content, setContent] = useState<InvoiceContent>(initial.content);
  const [server, setServer] = useState<Invoice>(initial);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [usePeriod, setUsePeriod] = useState(Boolean(initial.content.periodStart || initial.content.periodEnd));
  const etag = useRef(initialEtag);
  const dirty = useRef(false);
  const saving = useRef<Promise<boolean> | null>(null);
  const latest = useRef(content);
  // Declared before the autosave effect, so a save always sends the newest content.
  useEffect(() => {
    latest.current = content;
  }, [content]);

  const save = useCallback(async (): Promise<boolean> => {
    // One save at a time; a change made meanwhile is saved right after.
    while (saving.current) await saving.current;
    if (!dirty.current) return true;
    dirty.current = false;
    setSaveState("saving");
    const run = (async () => {
      try {
        const {
          data,
          error: problem,
          response,
        } = await browserApi.PUT("/invoices/{invoiceId}", {
          params: { path: { invoiceId: initial.id }, header: { "If-Match": etag.current } },
          body: latest.current,
        });
        if (data) {
          etag.current = response.headers.get("ETag") ?? etag.current;
          setServer(data);
          setSaveState(dirty.current ? "saving" : "saved");
          return true;
        }
        setSaveState(response.status === 412 ? "conflict" : "error");
        if (response.status !== 412) setError(te(problemCode(problem)));
        dirty.current = true;
        return false;
      } catch {
        setSaveState("error");
        dirty.current = true;
        return false;
      }
    })();
    saving.current = run;
    const ok = await run;
    saving.current = null;
    return ok;
  }, [initial.id, te]);

  useEffect(() => {
    if (!dirty.current) return;
    const timer = setTimeout(() => void save(), 700);
    return () => clearTimeout(timer);
  }, [content, save]);

  function change(patch: Partial<InvoiceContent>) {
    dirty.current = true;
    setSaveState("dirty");
    setContent((c) => ({ ...c, ...patch }));
  }
  function changeBuyer(patch: Partial<NonNullable<InvoiceContent["buyer"]>>) {
    dirty.current = true;
    setSaveState("dirty");
    setContent((c) => ({ ...c, buyer: { ...c.buyer, ...patch } }));
  }
  function changeLine(index: number, patch: Partial<InvoiceLineInput>) {
    dirty.current = true;
    setSaveState("dirty");
    setContent((c) => ({ ...c, lines: (c.lines ?? []).map((l, i) => (i === index ? { ...l, ...patch } : l)) }));
  }

  function pickCustomer(id: string) {
    const customer = customers.find((x) => x.id === id);
    if (!customer) return;
    change({
      customerId: customer.id,
      buyer: {
        customerNumber: customer.customerNumber,
        name: customer.name,
        street: customer.street,
        additionalLine: customer.additionalLine,
        postalCode: customer.postalCode,
        city: customer.city,
        countryCode: customer.countryCode,
        vatId: customer.vatId,
        email: customer.email,
        contactName: customer.contactName,
      },
      vatTreatment: customer.vatTreatment,
      buyerReference: customer.buyerReference ?? content.buyerReference,
      format: customer.preferredFormat ?? content.format,
    });
  }

  function pickArticle(index: number, id: string) {
    const article = articles.find((a) => a.id === id);
    if (!article) return;
    changeLine(index, {
      articleId: article.id,
      articleNumber: article.articleNumber,
      name: article.name,
      description: article.description,
      unitCode: article.unitCode,
      netPrice: article.netPrice,
      vatRate: article.vatRate,
    });
  }

  const local = useMemo(() => clientProblems(content, settings), [content, settings]);
  // The server's answer wins once it has seen the current content; until then the local checks.
  const isSettings = (p: DraftProblem) => p.field.startsWith("seller.") || p.field.startsWith("settings.");
  const fresh = saveState === "saved" || saveState === "idle";
  const problems: DraftProblem[] = fresh ? server.problems : [...local, ...server.problems.filter(isSettings)];
  const settingsProblems = problems.filter(isSettings);
  const klein = settings?.kleinunternehmer ?? false;
  const treatment = content.vatTreatment ?? "DOMESTIC";
  const showRate = !klein && treatment === "DOMESTIC";
  const msg = (field: string) => {
    const p = problemFor(problems, field);
    return p ? t(`problem.${p.code}`) : undefined;
  };
  const fieldLabel = (field: string) => {
    const { line, key } = splitField(field);
    const name = t.has(`field.${key}`) ? t(`field.${key}`) : key;
    return line ? `${t("line", { n: line })}: ${name}` : name;
  };
  const money = (v: string | undefined) => (v ? format.number(Number(v), { style: "currency", currency: "EUR" }) : "–");

  async function issue() {
    setBusy(true);
    setError(null);
    try {
      dirty.current = true;
      if (!(await save())) return;
      const { error: problem, response } = await browserApi.POST("/invoices/{invoiceId}/issue", {
        params: { path: { invoiceId: initial.id }, header: { "Idempotency-Key": idempotencyKey() } },
      });
      if (response.status === 202) router.refresh();
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function discard() {
    if (!window.confirm(t("discardConfirm"))) return;
    const { response, error: problem } = await browserApi.DELETE("/invoices/{invoiceId}", {
      params: { path: { invoiceId: initial.id } },
    });
    if (response.status === 204) router.push("/invoices");
    else setError(te(problemCode(problem)));
  }

  const lines = content.lines ?? [];
  const totals = server.totals;
  const statusText = {
    idle: "",
    dirty: "",
    saving: t("saving"),
    saved: t("saved"),
    error: t("saveFailed"),
    conflict: t("conflict"),
  }[saveState];

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-baseline justify-between gap-3">
        <h1 className="text-2xl font-semibold">{t("titleDraft", { kind: tc(`kind.${initial.kind}`) })}</h1>
        <p role="status" aria-live="polite" className="text-sm text-slate-600" data-testid="save-state">
          {statusText}
        </p>
      </header>
      {initial.original && <p className="text-slate-700">{t("originalRef", { number: initial.original.number })}</p>}

      {(server.issueError?.length ?? 0) > 0 && (
        <div className="rounded border border-red-700 bg-red-50 px-4 py-3 text-red-900" data-testid="issue-error">
          <p className="font-medium">{t("issueFailed")}</p>
          <ul className="mt-2 list-disc pl-5">
            {server.issueError!.map((m, i) => (
              <li key={i}>
                {m.ruleId}: {m.text}
              </li>
            ))}
          </ul>
        </div>
      )}
      <ErrorAlert message={error} />

      <fieldset disabled={!canEdit} className="space-y-8">
        <section aria-labelledby="customer-heading" className="space-y-4">
          <h2 id="customer-heading" className="text-lg font-semibold">
            {t("customerHeading")}
          </h2>
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-64 space-y-1">
              <label htmlFor="pick-customer" className="block font-medium">
                {t("pickCustomer")}
              </label>
              <select
                id="pick-customer"
                className={input}
                value={content.customerId ?? ""}
                onChange={(e) => pickCustomer(e.target.value)}
              >
                <option value="">{t("pickCustomerNone")}</option>
                {customers.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                    {c.customerNumber ? ` (${c.customerNumber})` : ""}
                  </option>
                ))}
              </select>
            </div>
            <Link href="/customers" className="pb-2 text-brand-700 underline">
              {t("manageCustomers")}
            </Link>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            {(
              [
                ["name", "buyerName", "organization"],
                ["customerNumber", "buyerNumber", undefined],
                ["street", "buyerStreet", "address-line1"],
                ["additionalLine", "buyerAdditional", "address-line2"],
                ["postalCode", "buyerPostalCode", "postal-code"],
                ["city", "buyerCity", "address-level2"],
                ["countryCode", "buyerCountry", undefined],
                ["vatId", "buyerVatId", undefined],
                ["email", "buyerEmail", "email"],
                ["contactName", "buyerContact", undefined],
              ] as const
            ).map(([key, label, autoComplete]) => (
              <Labeled key={key} id={`buyer-${key}`} label={t(label)} problem={msg(`buyer.${key}`)}>
                {(p) => (
                  <input
                    {...p}
                    className={input}
                    autoComplete={autoComplete ?? "off"}
                    value={content.buyer?.[key] ?? ""}
                    maxLength={key === "email" ? 320 : 200}
                    onChange={(e) =>
                      changeBuyer({ [key]: key === "countryCode" ? e.target.value.toUpperCase() : e.target.value })
                    }
                  />
                )}
              </Labeled>
            ))}
          </div>
        </section>

        <section aria-labelledby="details-heading" className="space-y-4">
          <h2 id="details-heading" className="text-lg font-semibold">
            {t("detailsHeading")}
          </h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <Labeled
              id="format"
              label={t("format")}
              hint={content.format ? tc(`formatHint.${content.format}`) : undefined}
            >
              {(p) => (
                <select
                  {...p}
                  className={input}
                  value={content.format ?? ""}
                  onChange={(e) => change({ format: e.target.value as InvoiceContent["format"] })}
                >
                  {FORMATS.map((f) => (
                    <option key={f} value={f}>
                      {tc(`format.${f}`)}
                    </option>
                  ))}
                </select>
              )}
            </Labeled>
            {klein ? (
              <p className="rounded border border-slate-300 bg-slate-50 px-3 py-2 text-sm">
                {t("kleinunternehmerNote")}
              </p>
            ) : (
              <Labeled id="vatTreatment" label={t("vatTreatment")} problem={msg("vatTreatment")}>
                {(p) => (
                  <select
                    {...p}
                    className={input}
                    value={treatment}
                    onChange={(e) => change({ vatTreatment: e.target.value as InvoiceContent["vatTreatment"] })}
                  >
                    {TREATMENTS.map((v) => (
                      <option key={v} value={v}>
                        {tc(`vatTreatment.${v}`)}
                      </option>
                    ))}
                  </select>
                )}
              </Labeled>
            )}
            <Labeled id="issueDate" label={t("issueDate")} hint={t("issueDateHint")}>
              {(p) => (
                <input
                  {...p}
                  type="date"
                  className={input}
                  value={content.issueDate ?? ""}
                  onChange={(e) => change({ issueDate: e.target.value || undefined })}
                />
              )}
            </Labeled>
            <Labeled
              id="dueDate"
              label={t("dueDate")}
              hint={t("dueDateHint", { days: settings?.paymentTermsDays ?? 14 })}
              problem={msg("dueDate")}
            >
              {(p) => (
                <input
                  {...p}
                  type="date"
                  className={input}
                  value={content.dueDate ?? ""}
                  onChange={(e) => change({ dueDate: e.target.value || undefined })}
                />
              )}
            </Labeled>
            {usePeriod ? (
              <>
                <Labeled id="periodStart" label={t("periodStart")} problem={msg("periodStart")}>
                  {(p) => (
                    <input
                      {...p}
                      type="date"
                      className={input}
                      value={content.periodStart ?? ""}
                      onChange={(e) => change({ periodStart: e.target.value || undefined })}
                    />
                  )}
                </Labeled>
                <Labeled id="periodEnd" label={t("periodEnd")} problem={msg("periodEnd")}>
                  {(p) => (
                    <input
                      {...p}
                      type="date"
                      className={input}
                      value={content.periodEnd ?? ""}
                      onChange={(e) => change({ periodEnd: e.target.value || undefined })}
                    />
                  )}
                </Labeled>
              </>
            ) : (
              <Labeled id="deliveryDate" label={t("deliveryDate")} problem={msg("deliveryDate")}>
                {(p) => (
                  <input
                    {...p}
                    type="date"
                    className={input}
                    value={content.deliveryDate ?? ""}
                    onChange={(e) => change({ deliveryDate: e.target.value || undefined })}
                  />
                )}
              </Labeled>
            )}
            <div className="flex items-center gap-2 sm:col-span-2">
              <input
                id="usePeriod"
                type="checkbox"
                checked={usePeriod}
                onChange={(e) => {
                  setUsePeriod(e.target.checked);
                  change(
                    e.target.checked ? { deliveryDate: undefined } : { periodStart: undefined, periodEnd: undefined },
                  );
                }}
              />
              <label htmlFor="usePeriod">{t("usePeriod")}</label>
            </div>
            <Labeled
              id="buyerReference"
              label={t("buyerReference")}
              hint={isXRechnung(content.format) ? t("buyerReferenceHint") : undefined}
              problem={msg("buyerReference")}
            >
              {(p) => (
                <input
                  {...p}
                  className={input}
                  maxLength={100}
                  value={content.buyerReference ?? ""}
                  onChange={(e) => change({ buyerReference: e.target.value })}
                />
              )}
            </Labeled>
            <Labeled id="orderReference" label={t("orderReference")}>
              {(p) => (
                <input
                  {...p}
                  className={input}
                  maxLength={100}
                  value={content.orderReference ?? ""}
                  onChange={(e) => change({ orderReference: e.target.value })}
                />
              )}
            </Labeled>
            <div className="sm:col-span-2">
              <Labeled id="paymentTerms" label={t("paymentTerms")} hint={t("paymentTermsHint")}>
                {(p) => (
                  <input
                    {...p}
                    className={input}
                    maxLength={500}
                    value={content.paymentTerms ?? ""}
                    onChange={(e) => change({ paymentTerms: e.target.value })}
                  />
                )}
              </Labeled>
            </div>
            <div className="sm:col-span-2">
              <Labeled id="note" label={t("note")}>
                {(p) => (
                  <textarea
                    {...p}
                    className={input}
                    rows={2}
                    maxLength={2000}
                    value={content.note ?? ""}
                    onChange={(e) => change({ note: e.target.value })}
                  />
                )}
              </Labeled>
            </div>
          </div>
        </section>

        <section aria-labelledby="lines-heading" className="space-y-4">
          <h2 id="lines-heading" className="text-lg font-semibold">
            {t("linesHeading")}
          </h2>
          {msg("lines") && <p className="text-red-800">{`${t("field.lines")}: ${msg("lines")}`}</p>}
          <ol className="space-y-4">
            {lines.map((line, i) => {
              const f = `lines[${i}]`;
              return (
                <li key={i} className="rounded border border-slate-200 p-4" data-testid={`line-${i}`}>
                  <fieldset className="space-y-3">
                    <legend className="font-medium">{t("line", { n: i + 1 })}</legend>
                    {articles.length > 0 && (
                      <div className="max-w-sm space-y-1">
                        <label htmlFor={`${f}-article`} className="block text-sm">
                          {t("pickArticle")}
                        </label>
                        <select
                          id={`${f}-article`}
                          className={input}
                          value={line.articleId ?? ""}
                          onChange={(e) => pickArticle(i, e.target.value)}
                        >
                          <option value="">{t("pickArticleNone")}</option>
                          {articles.map((a) => (
                            <option key={a.id} value={a.id}>
                              {a.name}
                            </option>
                          ))}
                        </select>
                      </div>
                    )}
                    <div className="grid gap-3 sm:grid-cols-6">
                      <div className="sm:col-span-6">
                        <Labeled id={`${f}-name`} label={t("lineName")} problem={msg(`${f}.name`)}>
                          {(p) => (
                            <input
                              {...p}
                              className={input}
                              maxLength={200}
                              value={line.name ?? ""}
                              onChange={(e) => changeLine(i, { name: e.target.value })}
                            />
                          )}
                        </Labeled>
                      </div>
                      <div className="sm:col-span-6">
                        <Labeled id={`${f}-description`} label={t("lineDescription")}>
                          {(p) => (
                            <input
                              {...p}
                              className={input}
                              maxLength={1000}
                              value={line.description ?? ""}
                              onChange={(e) => changeLine(i, { description: e.target.value })}
                            />
                          )}
                        </Labeled>
                      </div>
                      <Labeled id={`${f}-quantity`} label={t("lineQuantity")} problem={msg(`${f}.quantity`)}>
                        {(p) => (
                          <input
                            {...p}
                            className={input}
                            inputMode="decimal"
                            value={line.quantity ?? ""}
                            onChange={(e) => changeLine(i, { quantity: normaliseDecimal(e.target.value) })}
                          />
                        )}
                      </Labeled>
                      <Labeled id={`${f}-unit`} label={t("lineUnit")} problem={msg(`${f}.unitCode`)}>
                        {(p) => (
                          <select
                            {...p}
                            className={input}
                            value={line.unitCode ?? ""}
                            onChange={(e) => changeLine(i, { unitCode: e.target.value })}
                          >
                            {!UNITS.some((u) => u === line.unitCode) && line.unitCode && (
                              <option value={line.unitCode}>{line.unitCode}</option>
                            )}
                            {UNITS.map((u) => (
                              <option key={u} value={u}>
                                {tc(`unit.${u}`)}
                              </option>
                            ))}
                          </select>
                        )}
                      </Labeled>
                      <div className="sm:col-span-2">
                        <Labeled id={`${f}-price`} label={t("linePrice")} problem={msg(`${f}.netPrice`)}>
                          {(p) => (
                            <input
                              {...p}
                              className={input}
                              inputMode="decimal"
                              value={line.netPrice ?? ""}
                              onChange={(e) => changeLine(i, { netPrice: normaliseDecimal(e.target.value) })}
                            />
                          )}
                        </Labeled>
                      </div>
                      {showRate && (
                        <Labeled id={`${f}-vat`} label={t("lineVat")} problem={msg(`${f}.vatRate`)}>
                          {(p) => (
                            <select
                              {...p}
                              className={input}
                              value={line.vatRate ?? ""}
                              onChange={(e) =>
                                changeLine(i, { vatRate: e.target.value as InvoiceLineInput["vatRate"] })
                              }
                            >
                              {RATES.map((r) => (
                                <option key={r} value={r}>
                                  {tc(`vatRate.${r}`)}
                                </option>
                              ))}
                            </select>
                          )}
                        </Labeled>
                      )}
                      <div className="space-y-1">
                        <span className="block font-medium">{t("lineAmount")}</span>
                        <span className="block py-2 tabular-nums">{money(totals?.lineAmounts?.[i])}</span>
                      </div>
                    </div>
                    {lines.length > 1 && (
                      <button
                        type="button"
                        className="text-sm text-red-800 underline"
                        onClick={() => change({ lines: lines.filter((_, j) => j !== i) })}
                      >
                        {t("removeLine", { n: i + 1 })}
                      </button>
                    )}
                  </fieldset>
                </li>
              );
            })}
          </ol>
          <button
            type="button"
            className="rounded border border-brand-700 px-3 py-1 text-brand-700"
            onClick={() => change({ lines: [...lines, { quantity: "1", unitCode: "C62", vatRate: "STANDARD_19" }] })}
          >
            {t("addLine")}
          </button>
        </section>
      </fieldset>

      <section aria-labelledby="totals-heading" className="max-w-md space-y-2">
        <h2 id="totals-heading" className="text-lg font-semibold">
          {t("totalsHeading")}
        </h2>
        <dl className="space-y-1" data-testid="totals">
          <div className="flex justify-between">
            <dt>{t("net")}</dt>
            <dd className="tabular-nums">{money(totals?.netAmount)}</dd>
          </div>
          {totals?.vat.map((v) => (
            <div key={`${v.category}-${v.rate}`} className="flex justify-between gap-4">
              <dt>{v.category === "S" ? t("vat", { rate: format.number(Number(v.rate)) }) : (v.note ?? v.category)}</dt>
              <dd className="tabular-nums">{money(v.taxAmount)}</dd>
            </div>
          ))}
          <div className="flex justify-between border-t border-slate-300 pt-1 font-semibold">
            <dt>{t("gross")}</dt>
            <dd className="tabular-nums" data-testid="gross">
              {money(totals?.grossAmount)}
            </dd>
          </div>
        </dl>
      </section>

      <section aria-labelledby="problems-heading" className="space-y-3">
        <h2 id="problems-heading" className="text-lg font-semibold">
          {t("problemsHeading")}
        </h2>
        {problems.length === 0 ? (
          <p className="text-green-800">{t("noProblems")}</p>
        ) : (
          <ul className="list-disc pl-5 text-red-900" data-testid="problems">
            {problems.map((p) => (
              <li key={`${p.field}-${p.code}`}>
                {fieldLabel(p.field)}: {t(`problem.${p.code}`)}
              </li>
            ))}
          </ul>
        )}
        {settingsProblems.length > 0 && (
          <p className="flex flex-wrap gap-4">
            <Link href="/settings/invoices" className="text-brand-700 underline">
              {t("goToSettings")}
            </Link>
            <Link href="/settings/workspace" className="text-brand-700 underline">
              {t("goToWorkspace")}
            </Link>
          </p>
        )}
      </section>

      {canEdit && (
        <div className="flex flex-wrap items-center gap-4">
          <button
            type="button"
            onClick={issue}
            disabled={busy || problems.length > 0}
            aria-busy={busy}
            aria-describedby="issue-hint"
            className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60"
            data-testid="issue"
          >
            {t("issue")}
          </button>
          <button type="button" onClick={discard} className="text-red-800 underline">
            {t("discard")}
          </button>
          <p id="issue-hint" className="w-full text-sm text-slate-600">
            {t("issueHint")}
          </p>
        </div>
      )}
    </div>
  );
}
