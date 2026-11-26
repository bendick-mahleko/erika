"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import {
  browserApi,
  problemCode,
  type BillableReport,
  type ReportInvoiceLine,
  type ReportInvoicePreview,
  type VatRate,
} from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

type Line = ReportInvoiceLine & { key: string };

const VAT_RATES: VatRate[] = ["STANDARD_19", "REDUCED_7", "ZERO"];
const DECIMAL = /^-?[0-9]{1,12}(\.[0-9]{1,4})?$/;

/** German input ("1,5") to the API's decimal ("1.5"). */
function decimal(value: string): string {
  const s = value.trim();
  return s.includes(",") ? s.replace(/\./g, "").replace(",", ".") : s;
}

/** quantity × price in cents (display only; the server calculates the invoice), or null if not a number. */
function cents(quantity: string, price: string): number | null {
  const q = decimal(quantity);
  const p = decimal(price);
  if (!DECIMAL.test(q) || !DECIMAL.test(p)) return null;
  return Math.round(Number(q) * Number(p) * 100);
}

/**
 * M-IV-01 to M-IV-04: choose a customer's signed reports, check and change the proposed lines,
 * then let the invoice creator issue the e-invoice (with the report PDFs embedded).
 */
export function ReportInvoicing({ reports }: { reports: BillableReport[] }) {
  const t = useTranslations("ReportInvoicing");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const customers = useMemo(() => {
    const byId = new Map<string, string>();
    for (const r of reports) byId.set(r.customerId, r.customerName);
    return [...byId.entries()].sort((a, b) => a[1].localeCompare(b[1], "de"));
  }, [reports]);
  const [customerId, setCustomerId] = useState(customers[0]?.[0] ?? "");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<ReportInvoicePreview | null>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const [propertyRelated, setPropertyRelated] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  const ofCustomer = reports.filter((r) => r.customerId === customerId);
  const euro = (c: number) => format.number(c / 100, { style: "currency", currency: "EUR" });
  const total = lines.reduce((sum, l) => sum + (cents(l.quantity, l.netPrice) ?? 0), 0);
  const invalid = lines.some((l) => !l.name.trim() || cents(l.quantity, l.netPrice) === null || l.priceMissing);

  function choose(id: string) {
    setCustomerId(id);
    setSelected(new Set());
    setPreview(null);
    setLines([]);
  }

  function toggle(id: string) {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setPreview(null);
  }

  async function loadPreview() {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/report-invoicing/preview", {
        body: { reportIds: [...selected] },
      });
      if (data) {
        setPreview(data);
        setLines(data.lines.map((l, i) => ({ ...l, key: `${i}`, netPrice: l.priceMissing ? "" : l.netPrice })));
        setIdempotencyKey(crypto.randomUUID());
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  function change(key: string, patch: Partial<Line>) {
    setLines((ls) =>
      ls.map((l) => (l.key === key ? { ...l, ...patch, ...("netPrice" in patch ? { priceMissing: false } : {}) } : l)),
    );
  }

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/report-invoicing/invoices", {
        params: { header: { "Idempotency-Key": idempotencyKey } },
        body: {
          reportIds: [...selected],
          propertyRelated: preview?.privateCustomer ? propertyRelated : false,
          lines: lines.map((l) => ({
            name: l.name.trim(),
            description: l.description?.trim() || undefined,
            quantity: decimal(l.quantity),
            unitCode: l.unitCode,
            netPrice: decimal(l.netPrice),
            vatRate: l.vatRate,
            laborCost: l.laborCost,
            articleId: l.articleId,
            articleNumber: l.articleNumber,
          })),
        },
      });
      if (data) {
        router.push(`/invoices/${data.invoiceId}`);
      } else {
        setError(te(problemCode(problem)));
        setBusy(false);
      }
    } catch {
      setError(te("generic"));
      setBusy(false);
    }
  }

  if (reports.length === 0) return <p className="text-slate-700">{t("empty")}</p>;
  const input = "w-full rounded border px-2 py-1";

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <div className="space-y-1">
        <label htmlFor="ri-customer" className="block font-medium">
          {t("customer")}
        </label>
        <select
          id="ri-customer"
          value={customerId}
          onChange={(e) => choose(e.target.value)}
          className="rounded border px-2 py-1"
          data-testid="ri-customer"
        >
          {customers.map(([id, name]) => (
            <option key={id} value={id}>
              {name}
            </option>
          ))}
        </select>
      </div>

      <fieldset className="space-y-2">
        <legend className="font-medium">{t("reports")}</legend>
        {ofCustomer.map((r) => (
          <label key={r.reportId} className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={selected.has(r.reportId)}
              onChange={() => toggle(r.reportId)}
              data-testid="ri-report"
            />
            <span>
              {t("reportLabel", {
                title: r.workOrderTitle,
                date: format.dateTime(new Date(r.signedAt), { dateStyle: "medium" }),
                version: r.version,
              })}
              {r.state === "NOT_SIGNED" && <span className="ml-2 text-sm text-amber-800">{t("notSigned")}</span>}
            </span>
          </label>
        ))}
      </fieldset>
      <button
        type="button"
        onClick={loadPreview}
        disabled={busy || selected.size === 0}
        className="rounded bg-brand-700 px-4 py-2 font-medium text-white disabled:opacity-60"
      >
        {t("preview")}
      </button>

      {preview && (
        <section aria-labelledby="ri-lines" className="space-y-4">
          <h2 id="ri-lines" className="text-lg font-semibold">
            {t("linesTitle")}
          </h2>
          <p className="max-w-3xl text-slate-700">{t("linesIntro")}</p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[56rem] text-left text-sm" data-testid="ri-lines">
              <thead className="border-b border-slate-300">
                <tr>
                  <th scope="col" className="py-2 pr-2">
                    {t("name")}
                  </th>
                  <th scope="col" className="py-2 pr-2">
                    {t("quantity")}
                  </th>
                  <th scope="col" className="py-2 pr-2">
                    {t("unit")}
                  </th>
                  <th scope="col" className="py-2 pr-2">
                    {t("price")}
                  </th>
                  <th scope="col" className="py-2 pr-2">
                    {t("vat")}
                  </th>
                  <th scope="col" className="py-2 pr-2">
                    {t("labour")}
                  </th>
                  <th scope="col" className="py-2 pr-2 text-right">
                    {t("net")}
                  </th>
                  <th scope="col" className="py-2">
                    <span className="sr-only">{t("remove")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l, i) => {
                  const net = cents(l.quantity, l.netPrice);
                  return (
                    <tr key={l.key} className="border-b border-slate-100 align-top">
                      <td className="py-2 pr-2">
                        <input
                          aria-label={t("nameOf", { n: i + 1 })}
                          value={l.name}
                          maxLength={200}
                          onChange={(e) => change(l.key, { name: e.target.value })}
                          className={input}
                        />
                        {l.description && <p className="mt-1 text-xs text-slate-600">{l.description}</p>}
                      </td>
                      <td className="w-24 py-2 pr-2">
                        <input
                          aria-label={t("quantityOf", { n: i + 1 })}
                          inputMode="decimal"
                          value={l.quantity}
                          onChange={(e) => change(l.key, { quantity: e.target.value })}
                          className={input}
                        />
                      </td>
                      <td className="w-16 py-2 pr-2">{l.unitCode}</td>
                      <td className="w-28 py-2 pr-2">
                        <input
                          aria-label={t("priceOf", { n: i + 1 })}
                          aria-invalid={l.priceMissing || net === null}
                          inputMode="decimal"
                          value={l.netPrice}
                          onChange={(e) => change(l.key, { netPrice: e.target.value })}
                          className={`${input} ${l.priceMissing ? "border-amber-600" : ""}`}
                          data-testid="ri-price"
                        />
                        {l.priceMissing && <p className="mt-1 text-xs text-amber-800">{t("priceMissing")}</p>}
                      </td>
                      <td className="w-28 py-2 pr-2">
                        <select
                          aria-label={t("vatOf", { n: i + 1 })}
                          value={l.vatRate}
                          onChange={(e) => change(l.key, { vatRate: e.target.value as VatRate })}
                          className="rounded border px-1 py-1"
                        >
                          {VAT_RATES.map((v) => (
                            <option key={v} value={v}>
                              {t(`vatRates.${v}`)}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="py-2 pr-2 text-center">
                        <input
                          type="checkbox"
                          aria-label={t("labourOf", { n: i + 1 })}
                          checked={l.laborCost}
                          onChange={(e) => change(l.key, { laborCost: e.target.checked })}
                        />
                      </td>
                      <td className="py-2 pr-2 text-right tabular-nums">{net === null ? "–" : euro(net)}</td>
                      <td className="py-2">
                        <button
                          type="button"
                          onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}
                          className="text-sm text-red-700 underline"
                        >
                          {t("remove")}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan={6} className="py-2 pr-2 text-right font-medium">
                    {t("totalNet")}
                  </td>
                  <td className="py-2 pr-2 text-right font-semibold tabular-nums" data-testid="ri-total">
                    {euro(total)}
                  </td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
          <button
            type="button"
            onClick={() =>
              setLines((ls) => [
                ...ls,
                {
                  key: crypto.randomUUID(),
                  kind: "OTHER",
                  name: "",
                  quantity: "1",
                  unitCode: "C62",
                  netPrice: "",
                  vatRate: "STANDARD_19",
                  laborCost: false,
                },
              ])
            }
            className="rounded border px-3 py-1 text-sm"
          >
            {t("addLine")}
          </button>
          {preview.privateCustomer && (
            <div className="space-y-2 rounded border border-slate-200 p-3">
              <p className="text-slate-700">{t("privateHint")}</p>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={propertyRelated}
                  onChange={(e) => setPropertyRelated(e.target.checked)}
                />
                {t("propertyRelated")}
              </label>
            </div>
          )}
          <div className="space-y-2">
            <button
              type="button"
              onClick={create}
              disabled={busy || invalid || lines.length === 0}
              className="rounded bg-brand-700 px-4 py-2 font-medium text-white disabled:opacity-60"
              data-testid="ri-create"
            >
              {t("create")}
            </button>
            {invalid && <p className="text-sm text-amber-800">{t("fixLines")}</p>}
          </div>
        </section>
      )}
    </div>
  );
}
