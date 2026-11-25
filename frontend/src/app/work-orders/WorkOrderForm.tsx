"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import {
  browserApi,
  idempotencyKey,
  problemCode,
  type ChecklistTemplate,
  type Customer,
  type Site,
  type StaffMember,
  type WorkOrder,
  type WorkOrderInput,
  type WorkOrderStatus,
} from "@/lib/api/browser";
import { hhmm, isoDay, LOCKED, toInstant, USER_STATUSES } from "@/lib/workorders";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

const select = "block w-full rounded border border-slate-400 px-3 py-2 disabled:bg-slate-100";

/** Create or change a work order: customer, site, time, people, checklist, status (M-OF-02). */
export function WorkOrderForm({
  customers,
  staff,
  templates,
  initial,
  canEdit,
}: {
  customers: Customer[];
  staff: StaffMember[];
  templates: ChecklistTemplate[];
  initial?: { workOrder: WorkOrder; etag: string };
  canEdit: boolean;
}) {
  const t = useTranslations("WorkOrders");
  const te = useErrorText();
  const router = useRouter();
  const current = initial?.workOrder;
  const locked = current ? LOCKED.includes(current.status) : false;
  const editable = canEdit && !locked;

  const [etag, setEtag] = useState(initial?.etag ?? "");
  const [customerId, setCustomerId] = useState(current?.customerId ?? "");
  const [sites, setSites] = useState<Site[]>([]);
  const [siteId, setSiteId] = useState(current?.siteId ?? "");
  const [addingSite, setAddingSite] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!customerId) return;
    let cancelled = false;
    browserApi.GET("/sites", { params: { query: { customerId } } }).then(({ data }) => {
      if (!cancelled) setSites(data?.items ?? []);
    });
    return () => {
      cancelled = true;
    };
  }, [customerId]);

  async function addSite(form: HTMLFormElement | null) {
    const field = (name: string) =>
      (form?.querySelector<HTMLInputElement>(`[name="${name}"]`)?.value ?? "").trim() || undefined;
    const name = field("siteName");
    if (!name || !customerId) return;
    setError(null);
    const { data, error: problem } = await browserApi.POST("/sites", {
      params: { header: { "Idempotency-Key": idempotencyKey() } },
      body: {
        customerId,
        name,
        street: field("siteStreet"),
        postalCode: field("sitePostalCode"),
        city: field("siteCity"),
        countryCode: "DE",
        archived: false,
      },
    });
    if (data) {
      setSites((list) => [...list, data].sort((a, b) => a.name.localeCompare(b.name, "de")));
      setSiteId(data.id);
      setAddingSite(false);
      setMessage(t("siteAdded"));
    } else {
      setError(te(problemCode(problem)));
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const day = text("date");
    const body: WorkOrderInput = {
      customerId,
      siteId: siteId || undefined,
      title: text("title"),
      description: text("description") || undefined,
      plannedFrom: toInstant(day, text("from")),
      plannedTo: toInstant(day, text("to")),
      assigneeIds: form.getAll("assigneeIds").map(String),
      checklistTemplateId: text("checklistTemplateId") || undefined,
      status: current ? (text("status") as WorkOrderStatus) : undefined,
    };
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      if (current) {
        const {
          data,
          error: problem,
          response,
        } = await browserApi.PUT("/work-orders/{workOrderId}", {
          params: { path: { workOrderId: current.id }, header: { "If-Match": etag } },
          body,
        });
        if (data) {
          setEtag(response.headers.get("ETag") ?? "");
          setMessage(t("saved"));
          router.refresh();
        } else {
          setError(te(problemCode(problem)));
        }
      } else {
        const { data, error: problem } = await browserApi.POST("/work-orders", {
          params: { header: { "Idempotency-Key": idempotencyKey() } },
          body,
        });
        if (data) {
          router.push(`/work-orders/${data.id}`);
        } else {
          setError(te(problemCode(problem)));
        }
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const from = current?.plannedFrom ? new Date(current.plannedFrom) : undefined;
  const to = current?.plannedTo ? new Date(current.plannedTo) : undefined;
  const choosable = customers.filter((c) => !c.archived || c.id === current?.customerId);

  if (customers.length === 0 && !current) {
    return (
      <p className="text-slate-700">
        {t("noCustomers")}{" "}
        <a href="/customers" className="text-brand-700 underline">
          {t("customersLink")}
        </a>
      </p>
    );
  }

  return (
    <form method="post" onSubmit={submit} className="max-w-3xl space-y-6" data-testid="work-order-form">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      {locked && <p className="rounded border border-slate-300 bg-slate-50 px-4 py-3">{t("lockedHint")}</p>}
      {!canEdit && <p className="text-slate-700">{t("readOnlyHint")}</p>}
      <fieldset disabled={!editable || busy} className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1 sm:col-span-2">
          <label htmlFor="customerId" className="block font-medium">
            {t("customer")}
          </label>
          <select
            id="customerId"
            name="customerId"
            required
            className={select}
            value={customerId}
            onChange={(e) => {
              setCustomerId(e.target.value);
              setSiteId("");
              setSites([]);
            }}
          >
            <option value="">{t("chooseCustomer")}</option>
            {choosable.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
                {c.city ? ` (${c.city})` : ""}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1 sm:col-span-2">
          <label htmlFor="siteId" className="block font-medium">
            {t("site")}
          </label>
          <div className="flex flex-wrap gap-3">
            <select
              id="siteId"
              name="siteId"
              className={`${select} sm:w-auto sm:min-w-72`}
              value={siteId}
              onChange={(e) => setSiteId(e.target.value)}
              disabled={!customerId || !editable}
            >
              <option value="">{t("siteDefault")}</option>
              {sites
                .filter((s) => !s.archived || s.id === siteId)
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {[s.name, s.street, s.city].filter(Boolean).join(", ")}
                  </option>
                ))}
            </select>
            {editable && customerId && !addingSite && (
              <button type="button" className="text-brand-700 underline" onClick={() => setAddingSite(true)}>
                {t("newSite")}
              </button>
            )}
          </div>
        </div>
        {addingSite && (
          <div
            className="grid gap-3 rounded border border-slate-300 p-3 sm:col-span-2 sm:grid-cols-2"
            data-testid="new-site"
          >
            <Field id="siteName" label={t("siteName")} maxLength={200} />
            <Field id="siteStreet" label={t("siteStreet")} maxLength={200} />
            <Field id="sitePostalCode" label={t("sitePostalCode")} maxLength={20} />
            <Field id="siteCity" label={t("siteCity")} maxLength={100} />
            <div className="flex gap-3 sm:col-span-2">
              <button
                type="button"
                className="rounded border border-brand-700 px-3 py-1 text-brand-700"
                onClick={(e) => addSite(e.currentTarget.closest("form"))}
              >
                {t("addSite")}
              </button>
            </div>
          </div>
        )}
        <div className="sm:col-span-2">
          <Field id="title" label={t("titleField")} required maxLength={200} defaultValue={current?.title} />
        </div>
        <div className="space-y-1 sm:col-span-2">
          <label htmlFor="description" className="block font-medium">
            {t("description")}
          </label>
          <textarea
            id="description"
            name="description"
            rows={4}
            maxLength={4000}
            defaultValue={current?.description}
            className="block w-full rounded border border-slate-400 px-3 py-2"
          />
        </div>
        <Field id="date" label={t("date")} type="date" defaultValue={from ? isoDay(from) : undefined} />
        <div className="grid grid-cols-2 gap-3">
          <Field id="from" label={t("from")} type="time" defaultValue={from ? hhmm(from) : undefined} />
          <Field id="to" label={t("to")} type="time" defaultValue={to ? hhmm(to) : undefined} />
        </div>
        <fieldset className="space-y-2 sm:col-span-2">
          <legend className="font-medium">{t("assignTo")}</legend>
          {staff.length === 0 ? (
            <p className="text-slate-700">{t("noStaff")}</p>
          ) : (
            <ul className="grid gap-2 sm:grid-cols-2">
              {staff.map((s) => (
                <li key={s.userId} className="flex items-center gap-2">
                  <input
                    id={`assignee-${s.userId}`}
                    type="checkbox"
                    name="assigneeIds"
                    value={s.userId}
                    defaultChecked={current?.assigneeIds.includes(s.userId)}
                    className="h-5 w-5"
                  />
                  <label htmlFor={`assignee-${s.userId}`}>{s.displayName}</label>
                </li>
              ))}
            </ul>
          )}
        </fieldset>
        <div className="space-y-1">
          <label htmlFor="checklistTemplateId" className="block font-medium">
            {t("checklist")}
          </label>
          <select
            id="checklistTemplateId"
            name="checklistTemplateId"
            className={select}
            defaultValue={current?.checklistTemplateId ?? ""}
          >
            <option value="">{t("noChecklist")}</option>
            {templates
              .filter((tpl) => !tpl.archived || tpl.id === current?.checklistTemplateId)
              .map((tpl) => (
                <option key={tpl.id} value={tpl.id}>
                  {tpl.name}
                </option>
              ))}
          </select>
        </div>
        {current && (
          <div className="space-y-1">
            <label htmlFor="status" className="block font-medium">
              {t("status")}
            </label>
            <select id="status" name="status" className={select} defaultValue={current.status}>
              {(locked ? [current.status] : USER_STATUSES).map((s) => (
                <option key={s} value={s}>
                  {t(`statuses.${s}`)}
                </option>
              ))}
            </select>
          </div>
        )}
      </fieldset>
      {editable && <SubmitButton busy={busy}>{current ? t("save") : t("create")}</SubmitButton>}
    </form>
  );
}
