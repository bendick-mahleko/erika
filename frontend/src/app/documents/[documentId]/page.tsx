import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getFormatter, getTranslations } from "next-intl/server";
import { fetchDocument, fetchSession } from "@/lib/api/client";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Document");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/**
 * One document: what was received, how it was classified (IN-04), and the validation
 * result with plain-language reasons (VW-04). The full invoice viewer follows in Stage 6.
 */
export default async function DocumentPage({ params }: { params: Promise<{ documentId: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const { documentId } = await params;
  const doc = await fetchDocument(documentId);
  if (!doc) notFound();
  const t = await getTranslations("Document");
  const ti = await getTranslations("Inbox");
  const format = await getFormatter();
  const money = (value?: string) =>
    value ? format.number(Number(value), { style: "currency", currency: doc.invoice?.currency ?? "EUR" }) : undefined;
  const date = (value?: string) => (value ? format.dateTime(new Date(value), { dateStyle: "medium" }) : undefined);

  const facts: [string, string | undefined][] = [
    [t("facts.supplier"), doc.invoice?.sellerName],
    [t("facts.supplierTaxId"), doc.invoice?.sellerTaxId],
    [t("facts.buyer"), doc.invoice?.buyerName],
    [t("facts.number"), doc.invoice?.invoiceNumber],
    [t("facts.issueDate"), date(doc.invoice?.issueDate)],
    [t("facts.dueDate"), date(doc.invoice?.dueDate)],
    [t("facts.net"), money(doc.invoice?.netAmount)],
    [t("facts.tax"), money(doc.invoice?.taxAmount)],
    [t("facts.gross"), money(doc.invoice?.grossAmount)],
    [t("facts.file"), doc.fileName],
    [t("facts.received"), format.dateTime(new Date(doc.receivedAt), { dateStyle: "medium", timeStyle: "short" })],
    [t("facts.via"), ti(`via.${doc.receivedVia}`)],
    [t("facts.format"), t(`format.${doc.format}`)],
    [t("facts.profile"), doc.profile],
    [t("facts.sha256"), doc.sha256],
  ];

  const validation = doc.validation;
  const levelStyle =
    validation?.level === "VALID"
      ? "border-green-700 bg-green-50 text-green-900"
      : validation?.level === "WARNING"
        ? "border-amber-700 bg-amber-50 text-amber-900"
        : "border-red-700 bg-red-50 text-red-900";

  return (
    <div className="space-y-6">
      <p>
        <Link href="/inbox" className="text-brand-700 underline">
          {t("back")}
        </Link>
      </p>
      <h1 className="text-2xl font-semibold break-all">{doc.invoice?.sellerName ?? doc.fileName ?? t("title")}</h1>

      {doc.processing === "PENDING" && <p role="status">{t("processing")}</p>}
      {doc.processing === "FAILED" && <p className="text-red-800">{t("processingFailed")}</p>}

      {doc.notEInvoiceReason ? (
        <section className="rounded border border-slate-400 bg-slate-50 p-4" data-testid="not-einvoice">
          <h2 className="font-semibold">{t("notEInvoice")}</h2>
          <p className="mt-1">{t(`notEInvoiceReason.${doc.notEInvoiceReason}`)}</p>
        </section>
      ) : (
        validation && (
          <p className={`rounded border px-4 py-3 font-medium ${levelStyle}`} data-testid="validation-level">
            {t(`level.${validation.level}`)}
          </p>
        )
      )}
      {doc.duplicateOf && (
        <p className="rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900">
          <strong>{t("duplicate")}:</strong> {t("duplicateHint")}{" "}
          <Link href={`/documents/${doc.duplicateOf}`} className="underline">
            {t("title")}
          </Link>
        </p>
      )}

      <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-[max-content_1fr]">
        {facts
          .filter(([, value]) => value)
          .map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="font-medium text-slate-700">{label}</dt>
              <dd className="break-all">{value}</dd>
            </div>
          ))}
      </dl>

      {validation && !doc.notEInvoiceReason && (
        <section aria-labelledby="validation-heading" className="space-y-3">
          <h2 id="validation-heading" className="text-lg font-semibold">
            {t("validationHeading")}
          </h2>
          <p className="text-sm text-slate-600">
            {t("pack", {
              pack: validation.packId,
              date: format.dateTime(new Date(validation.checkedAt), { dateStyle: "medium" }),
            })}
          </p>
          {validation.messages.length === 0 ? (
            <p>{t("noMessages")}</p>
          ) : (
            <ul className="space-y-2">
              {validation.messages.map((m, i) => (
                <li key={`${m.ruleId}-${i}`} className="rounded border border-slate-200 p-3">
                  <p>
                    <span
                      className={m.severity === "ERROR" ? "font-semibold text-red-800" : "font-semibold text-amber-800"}
                    >
                      {t(`severity.${m.severity}`)}
                    </span>{" "}
                    <span className="text-sm text-slate-600">({m.ruleId})</span>
                  </p>
                  <p className="mt-1">{m.text}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      <p className="text-sm text-slate-600">{t("viewerNote")}</p>
    </div>
  );
}
