"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Invoice } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field } from "@/components/forms";

/**
 * An issued invoice: facts, download, sending by email with the delivery log (CR-07) and
 * corrections by credit note, cancellation or corrected invoice (CR-06). Nothing here changes
 * the invoice itself.
 */
export function IssuedInvoice({ invoice, canEdit }: { invoice: Invoice; canEdit: boolean }) {
  const t = useTranslations("Issued");
  const tc = useTranslations("Creator");
  const td = useTranslations("Document");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const issued = invoice.issued!;
  const deliveries = invoice.deliveries ?? [];
  const pending = deliveries.some((d) => d.status === "QUEUED");
  const isCorrection = invoice.kind === "CREDIT_NOTE" || invoice.kind === "CANCELLATION";
  const cancelled = issued.status === "CANCELLED";

  // While an email is queued, refresh until the worker has sent it.
  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => router.refresh(), 2000);
    return () => clearInterval(timer);
  }, [pending, router]);

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim() || undefined;
    setBusy(true);
    setError(null);
    setSent(false);
    try {
      const { data, error: problem } = await browserApi.POST("/invoices/{invoiceId}/send", {
        params: { path: { invoiceId: invoice.id }, header: { "Idempotency-Key": idempotencyKey() } },
        body: { to: text("to") ?? "", subject: text("subject"), message: text("message") },
      });
      if (data) {
        setSent(true);
        router.refresh();
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function correct(kind: "CREDIT_NOTE" | "CANCELLATION" | "CORRECTION") {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/invoices/{invoiceId}/credit-note", {
        params: { path: { invoiceId: invoice.id }, header: { "Idempotency-Key": idempotencyKey() } },
        body: { kind },
      });
      if (data) router.push(`/invoices/${data.id}`);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const fact = (label: string, value: string) => (
    <div className="flex gap-3">
      <dt className="w-40 shrink-0 text-slate-600">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
  const date = (d?: string) => (d ? format.dateTime(new Date(d), { dateStyle: "medium" }) : "–");

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold" data-testid="issued-title">
          {tc(`kind.${invoice.kind}`)} {issued.number}
        </h1>
        {invoice.original && (
          <p>
            {t("original")}:{" "}
            <Link href={`/invoices/${invoice.original.id}`} className="text-brand-700 underline">
              {invoice.original.number}
            </Link>
          </p>
        )}
      </header>
      <ErrorAlert message={error} />

      <dl className="space-y-1">
        {fact(t("number"), issued.number)}
        {fact(t("issueDate"), date(issued.issueDate))}
        {issued.dueDate && fact(t("dueDate"), date(issued.dueDate))}
        {fact(t("customer"), invoice.content.buyer?.name ?? "–")}
        {fact(t("format"), invoice.content.format ? tc(`format.${invoice.content.format}`) : "–")}
        {invoice.totals &&
          fact(
            td("facts.gross"),
            format.number(Number(invoice.totals.grossAmount), { style: "currency", currency: "EUR" }),
          )}
        <div className="flex gap-3">
          <dt className="w-40 shrink-0 text-slate-600">{t("status")}</dt>
          <dd data-testid="issued-status">{td(`status.${issued.status}`)}</dd>
        </div>
      </dl>
      <p className="flex flex-wrap gap-4">
        <a href={`/api/v1/documents/${issued.documentId}/original`} className="text-brand-700 underline" download>
          {t("download")} ({issued.fileName})
        </a>
        <Link href={`/documents/${issued.documentId}`} className="text-brand-700 underline">
          {t("openDocument")}
        </Link>
      </p>

      <section aria-labelledby="deliveries-heading" className="space-y-3">
        <h2 id="deliveries-heading" className="text-lg font-semibold">
          {t("deliveriesHeading")}
        </h2>
        {deliveries.length === 0 ? (
          <p className="text-slate-700">{t("noDeliveries")}</p>
        ) : (
          <ul className="space-y-1" data-testid="deliveries">
            {deliveries.map((d) => (
              <li key={d.id}>
                {t("deliveryEntry", {
                  at: format.dateTime(new Date(d.sentAt ?? d.createdAt), { dateStyle: "medium", timeStyle: "short" }),
                  to: d.to,
                })}{" "}
                – <span data-testid="delivery-status">{t(`deliveryStatus.${d.status}`)}</span>
                {d.detail && <span className="text-slate-600"> ({d.detail})</span>}
                {d.archived && (
                  <>
                    {" · "}
                    <a
                      href={`/api/v1/invoices/${invoice.id}/deliveries/${d.id}/message`}
                      className="text-brand-700 underline"
                    >
                      {t("emlDownload")}
                    </a>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {canEdit && !cancelled && (
        <section aria-labelledby="send-heading" className="max-w-xl space-y-3">
          <h2 id="send-heading" className="text-lg font-semibold">
            {t("sendHeading")}
          </h2>
          <form method="post" onSubmit={send} className="space-y-3">
            <fieldset disabled={busy} className="space-y-3">
              <Field
                id="to"
                label={t("sendTo")}
                type="email"
                required
                defaultValue={invoice.content.buyer?.email ?? ""}
                maxLength={320}
              />
              <Field
                id="subject"
                label={t("sendSubject")}
                hint={t("sendSubjectHint", { number: issued.number })}
                maxLength={200}
              />
              <div className="space-y-1">
                <label htmlFor="message" className="block font-medium">
                  {t("sendMessage")}
                </label>
                <textarea
                  id="message"
                  name="message"
                  rows={4}
                  maxLength={4000}
                  aria-describedby="message-hint"
                  className="block w-full rounded border border-slate-400 px-3 py-2"
                />
                <p id="message-hint" className="text-sm text-slate-600">
                  {t("sendMessageHint")}
                </p>
              </div>
            </fieldset>
            <button
              type="submit"
              disabled={busy}
              className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60"
              data-testid="send"
            >
              {t("send")}
            </button>
            <p role="status" className="text-green-800">
              {sent ? t("sent") : ""}
            </p>
          </form>
        </section>
      )}

      {(invoice.corrections?.length ?? 0) > 0 && (
        <section aria-labelledby="corrections-list" className="space-y-2">
          <h2 id="corrections-list" className="text-lg font-semibold">
            {t("correctionsList")}
          </h2>
          <ul className="list-disc pl-5">
            {invoice.corrections!.map((c) => (
              <li key={c.id}>
                <Link href={`/invoices/${c.id}`} className="text-brand-700 underline">
                  {c.kind ? tc(`kind.${c.kind}`) : ""} {c.number}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {canEdit && !isCorrection && !cancelled && (
        <section aria-labelledby="correct-heading" className="space-y-3">
          <h2 id="correct-heading" className="text-lg font-semibold">
            {t("correctionsHeading")}
          </h2>
          <p className="max-w-3xl text-slate-700">{t("correctionsHint")}</p>
          <div className="flex flex-wrap gap-3">
            {(
              [
                ["CREDIT_NOTE", "creditNote"],
                ["CANCELLATION", "cancellation"],
                ["CORRECTION", "correction"],
              ] as const
            ).map(([kind, label]) => (
              <button
                key={kind}
                type="button"
                disabled={busy}
                onClick={() => correct(kind)}
                className="rounded border border-brand-700 px-3 py-1 text-brand-700 disabled:opacity-60"
              >
                {t(label)}
              </button>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
