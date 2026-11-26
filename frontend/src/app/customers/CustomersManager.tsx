"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Customer, type CustomerInput } from "@/lib/api/browser";
import { FORMATS, TREATMENTS } from "@/lib/creator";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const select = "block w-full rounded border border-slate-400 px-3 py-2";

/** Customer list with an inline form for new and changed customers (CR-05). */
export function CustomersManager({ initial, canEdit }: { initial: Customer[]; canEdit: boolean }) {
  const t = useTranslations("Customers");
  const tc = useTranslations("Creator");
  const te = useErrorText();
  const router = useRouter();
  const [customers, setCustomers] = useState(initial);
  const [editing, setEditing] = useState<Customer | "new" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [query, setQuery] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim() || undefined;
    const days = text("paymentTermsDays");
    const body: CustomerInput = {
      name: text("name") ?? "",
      customerNumber: text("customerNumber"),
      street: text("street"),
      additionalLine: text("additionalLine"),
      postalCode: text("postalCode"),
      city: text("city"),
      countryCode: (text("countryCode") ?? "DE").toUpperCase(),
      vatId: text("vatId")?.replace(/\s/g, ""),
      email: text("email"),
      contactName: text("contactName"),
      phone: text("phone"),
      buyerReference: text("buyerReference"),
      vatTreatment: (text("vatTreatment") ?? "DOMESTIC") as CustomerInput["vatTreatment"],
      preferredFormat: text("preferredFormat") as CustomerInput["preferredFormat"],
      paymentTermsDays: days ? Number(days) : undefined,
      archived: form.get("archived") === "on",
      customerType: (text("customerType") ?? "BUSINESS") as CustomerInput["customerType"],
      leitwegId: text("leitwegId"),
    };
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      const result =
        editing === "new"
          ? await browserApi.POST("/customers", { params: { header: { "Idempotency-Key": idempotencyKey() } }, body })
          : await browserApi.PUT("/customers/{customerId}", {
              params: {
                path: { customerId: (editing as Customer).id },
                header: { "If-Match": `"${(editing as Customer).version}"` },
              },
              body,
            });
      if (result.data) {
        const saved = result.data;
        setCustomers((list) =>
          [...list.filter((c) => c.id !== saved.id), saved].sort((a, b) => a.name.localeCompare(b.name, "de")),
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
  const visible = customers.filter((c) =>
    [c.name, c.customerNumber, c.email].some((v) => v?.toLowerCase().includes(query.trim().toLowerCase())),
  );

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      <div className="flex flex-wrap items-end gap-4">
        <div className="w-72">
          <Field id="customer-search" label={t("search")} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {canEdit && editing === null && (
          <button
            type="button"
            className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
            onClick={() => setEditing("new")}
            data-testid="new-customer"
          >
            {t("new")}
          </button>
        )}
      </div>

      {editing !== null && (
        <form
          method="post"
          onSubmit={submit}
          className="max-w-2xl space-y-4 rounded border border-slate-300 p-4"
          key={current?.id ?? "new"}
        >
          <fieldset disabled={busy} className="grid gap-4 sm:grid-cols-2">
            <legend className="mb-2 font-semibold">{current ? current.name : t("new")}</legend>
            <Field id="name" label={t("name")} required maxLength={200} defaultValue={current?.name} />
            <div className="space-y-1">
              <label htmlFor="customerType" className="block font-medium">
                {t("customerType")}
              </label>
              <select
                id="customerType"
                name="customerType"
                className={select}
                defaultValue={current?.customerType ?? "BUSINESS"}
              >
                {(["BUSINESS", "PRIVATE"] as const).map((v) => (
                  <option key={v} value={v}>
                    {t(`customerTypes.${v}`)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              id="customerNumber"
              label={t("customerNumber")}
              maxLength={50}
              defaultValue={current?.customerNumber}
            />
            <Field id="street" label={t("street")} maxLength={200} defaultValue={current?.street} />
            <Field
              id="additionalLine"
              label={t("additionalLine")}
              maxLength={200}
              defaultValue={current?.additionalLine}
            />
            <Field id="postalCode" label={t("postalCode")} maxLength={20} defaultValue={current?.postalCode} />
            <Field id="city" label={t("city")} maxLength={100} defaultValue={current?.city} />
            <Field
              id="countryCode"
              label={t("countryCode")}
              required
              pattern="[A-Za-z]{2}"
              maxLength={2}
              defaultValue={current?.countryCode ?? "DE"}
            />
            <Field id="vatId" label={t("vatId")} maxLength={14} defaultValue={current?.vatId} />
            <Field id="email" label={t("email")} type="email" maxLength={320} defaultValue={current?.email} />
            <Field id="contactName" label={t("contactName")} maxLength={200} defaultValue={current?.contactName} />
            <Field id="phone" label={t("phone")} type="tel" maxLength={50} defaultValue={current?.phone} />
            <Field
              id="leitwegId"
              label={t("leitwegId")}
              hint={t("leitwegHint")}
              maxLength={46}
              pattern="[0-9]{2,12}(-[0-9A-Za-z]{1,30})?-[0-9]{2}"
              defaultValue={current?.leitwegId}
            />
            <Field
              id="buyerReference"
              label={t("buyerReference")}
              maxLength={100}
              defaultValue={current?.buyerReference}
            />
            <div className="space-y-1">
              <label htmlFor="vatTreatment" className="block font-medium">
                {t("vatTreatment")}
              </label>
              <select
                id="vatTreatment"
                name="vatTreatment"
                className={select}
                defaultValue={current?.vatTreatment ?? "DOMESTIC"}
              >
                {TREATMENTS.map((v) => (
                  <option key={v} value={v}>
                    {tc(`vatTreatment.${v}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label htmlFor="preferredFormat" className="block font-medium">
                {t("preferredFormat")}
              </label>
              <select
                id="preferredFormat"
                name="preferredFormat"
                className={select}
                defaultValue={current?.preferredFormat ?? ""}
              >
                <option value="">{t("preferredFormatDefault")}</option>
                {FORMATS.map((f) => (
                  <option key={f} value={f}>
                    {tc(`format.${f}`)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              id="paymentTermsDays"
              label={t("paymentTermsDays")}
              hint={t("paymentTermsDefault")}
              type="number"
              min={0}
              max={365}
              defaultValue={current?.paymentTermsDays}
            />
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
        <ul className="divide-y divide-slate-200" data-testid="customer-list">
          {visible.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
              <span>
                <span className="font-medium">{c.name}</span>
                {c.customerNumber && <span className="ml-2 text-slate-600">{c.customerNumber}</span>}
                <span className="ml-2 text-slate-600">{[c.postalCode, c.city].filter(Boolean).join(" ")}</span>
                {c.archived && <span className="ml-2 text-sm text-slate-500">({t("archived")})</span>}
              </span>
              {canEdit && (
                <button type="button" className="text-brand-700 underline" onClick={() => setEditing(c)}>
                  {t("edit")}
                  <span className="sr-only">: {c.name}</span>
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
