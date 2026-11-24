import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getFormatter, getLocale, getTranslations } from "next-intl/server";
import { fetchComments, fetchDocument, fetchDocumentView, fetchSession, type ViewSection } from "@/lib/api/client";
import { CorrectionRequest } from "./CorrectionRequest";
import { DocumentActions } from "./DocumentActions";
import { DocumentComments } from "./DocumentComments";
import { PaymentPanel } from "./PaymentPanel";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Document");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/**
 * The viewer (VW-01, VW-04, VW-06, VW-07): the invoice with a label for every business term in the
 * app's language (German or English),
 * the validation result with plain-language reasons, downloads of the original, the PDF
 * view copy and embedded attachments, and the status with its history (AR-03).
 */
export default async function DocumentPage({ params }: { params: Promise<{ documentId: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const { documentId } = await params;
  const doc = await fetchDocument(documentId);
  if (!doc) notFound();
  const lang = (await getLocale()) === "en" ? "en" : "de";
  const [view, comments] = await Promise.all([
    doc.viewAvailable ? fetchDocumentView(documentId, lang) : Promise.resolve(null),
    fetchComments(documentId),
  ]);
  const t = await getTranslations("Document");
  const ti = await getTranslations("Inbox");
  const format = await getFormatter();
  const api = `/api/v1/documents/${doc.id}`;

  const validation = doc.validation;
  const levelStyle =
    validation?.level === "VALID"
      ? "border-green-700 bg-green-50 text-green-900"
      : validation?.level === "WARNING"
        ? "border-amber-700 bg-amber-50 text-amber-900"
        : "border-red-700 bg-red-50 text-red-900";
  const sellerEmail = view ? findValue(view.sections, ["BT-43", "BT-34"]) : undefined;
  const canChange = session.role !== "ADVISOR";

  return (
    <div className="space-y-8">
      <p>
        <Link href="/inbox" className="text-brand-700 underline">
          {t("back")}
        </Link>
      </p>
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold break-words">
          {view?.title ?? doc.invoice?.sellerName ?? doc.fileName ?? t("title")}
        </h1>
        <p className="text-slate-700">
          {[doc.invoice?.sellerName, t(`format.${doc.format}`), ti(`via.${doc.receivedVia}`)]
            .filter(Boolean)
            .join(" · ")}
          {" · "}
          {format.dateTime(new Date(doc.receivedAt), { dateStyle: "medium", timeStyle: "short" })}
        </p>
      </header>

      {doc.processing === "PENDING" && (
        <p role="status" className="rounded border border-slate-300 bg-slate-50 px-4 py-3">
          {t("processing")}
        </p>
      )}
      {doc.processing === "FAILED" && <p className="text-red-800">{t("processingFailed")}</p>}

      {/* VW-04: the validation panel, green / amber / red, with the reasons in plain German. */}
      {doc.notEInvoiceReason ? (
        <section className="rounded border border-slate-400 bg-slate-50 p-4" data-testid="not-einvoice">
          <h2 className="font-semibold">{t("notEInvoice")}</h2>
          <p className="mt-1">{t(`notEInvoiceReason.${doc.notEInvoiceReason}`)}</p>
        </section>
      ) : (
        validation && (
          <section aria-labelledby="validation-heading" className={`space-y-3 rounded border p-4 ${levelStyle}`}>
            <h2 id="validation-heading" className="text-lg font-semibold" data-testid="validation-level">
              {t(`level.${validation.level}`)}
            </h2>
            <p className="text-sm">{t(`levelExplanation.${validation.level}`)}</p>
            {validation.messages.length > 0 && (
              <ul className="space-y-2">
                {validation.messages.map((m, i) => (
                  <li key={`${m.ruleId}-${i}`} className="rounded bg-white p-3 text-slate-900">
                    <p>
                      <span
                        className={
                          m.severity === "ERROR" ? "font-semibold text-red-800" : "font-semibold text-amber-800"
                        }
                      >
                        {t(`severity.${m.severity}`)}
                      </span>{" "}
                      <span className="text-sm text-slate-600">({m.ruleId})</span>
                    </p>
                    <p className="mt-1">{m.text}</p>
                    {m.originalText && (
                      <details className="mt-1 text-sm text-slate-600">
                        <summary>{t("originalRuleText")}</summary>
                        <p lang="en">{m.originalText}</p>
                      </details>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <p className="text-sm">
              {t("pack", {
                pack: validation.packId,
                date: format.dateTime(new Date(validation.checkedAt), { dateStyle: "medium" }),
              })}
            </p>
            {validation.level === "ERROR" && canChange && (
              <CorrectionRequest
                sellerEmail={sellerEmail}
                sellerName={doc.invoice?.sellerName}
                invoiceNumber={doc.invoice?.invoiceNumber}
                issueDate={doc.invoice?.issueDate}
                errors={validation.messages.filter((m) => m.severity === "ERROR").map((m) => m.text)}
              />
            )}
          </section>
        )
      )}
      {doc.duplicateOf && (
        <p className="rounded border border-amber-700 bg-amber-50 px-4 py-3 text-amber-900">
          <strong>{t("duplicate")}:</strong> {t("duplicateHint")}{" "}
          <Link href={`/documents/${doc.duplicateOf}`} className="underline">
            {t("openEarlier")}
          </Link>
        </p>
      )}

      <section aria-labelledby="downloads-heading" className="space-y-2">
        <h2 id="downloads-heading" className="text-lg font-semibold">
          {t("downloads")}
        </h2>
        <ul className="flex flex-wrap gap-3">
          <li>
            <a
              href={`${api}/original`}
              className="inline-block rounded border border-brand-700 px-3 py-1 text-brand-700"
              download
            >
              {t("downloadOriginal")}
            </a>
          </li>
          {doc.viewAvailable && (
            <li>
              <a
                href={`${api}/view-pdf`}
                className="inline-block rounded border border-brand-700 px-3 py-1 text-brand-700"
                download
              >
                {t("downloadViewPdf")}
              </a>
            </li>
          )}
          {view?.attachments.map((a) => (
            <li key={a.index}>
              <a
                href={`${api}/attachments/${a.index}`}
                className="inline-block rounded border border-slate-400 px-3 py-1"
                download
                data-testid="attachment-link"
              >
                {t("downloadAttachment", { name: a.fileName ?? `${a.index + 1}` })}
              </a>
            </li>
          ))}
        </ul>
        <p className="text-sm text-slate-600 break-all">
          {t("facts.sha256")}: <code>{doc.sha256}</code>
        </p>
      </section>

      {doc.transfer && <PaymentPanel transfer={doc.transfer} />}

      <DocumentActions doc={doc} canChange={canChange} />
      <DocumentComments documentId={doc.id} initial={comments} canComment={!session.workspace?.closed} />

      {view ? (
        <section aria-labelledby="invoice-heading" className="space-y-4" lang={view.language ?? "de"}>
          <h2 id="invoice-heading" className="text-lg font-semibold">
            {t("invoiceHeading")}
          </h2>
          {view.sections.map((s, i) => (
            <Section key={`${s.id}-${i}`} section={s} level={3} />
          ))}
        </section>
      ) : (
        doc.processing === "DONE" && !doc.notEInvoiceReason && <p className="text-slate-700">{t("noView")}</p>
      )}
    </div>
  );
}

/** One group of the invoice; nested groups (addresses, lines, VAT rates) get the next heading level. */
function Section({ section, level }: { section: ViewSection; level: number }) {
  const Heading = `h${Math.min(level, 6)}` as "h3" | "h4" | "h5" | "h6";
  const top = level === 3;
  return (
    <section
      className={top ? "rounded-lg border border-slate-200 p-4" : "mt-3 border-l-2 border-slate-200 pl-3"}
      data-section={section.id}
    >
      <Heading className={top ? "text-base font-semibold" : "font-medium"}>{section.title}</Heading>
      {section.fields.length > 0 && (
        <dl className="mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(10rem,max-content)_1fr]">
          {section.fields.map((f, i) => (
            <div key={`${f.bt}-${i}`} className="contents">
              <dt className="text-slate-700" title={f.bt}>
                {f.label}
              </dt>
              <dd className="break-words" data-bt={f.bt}>
                {f.value}
              </dd>
            </div>
          ))}
        </dl>
      )}
      {section.sections.map((s, i) => (
        <Section key={`${s.id}-${i}`} section={s} level={level + 1} />
      ))}
    </section>
  );
}

function findValue(sections: ViewSection[], bts: string[]): string | undefined {
  for (const bt of bts) {
    const found = sections
      .flatMap(function all(s): ViewSection["fields"] {
        return [...s.fields, ...s.sections.flatMap(all)];
      })
      .find((f) => f.bt === bt && f.value.includes("@"));
    if (found) return found.value.replace(/\s*\(Schema.*\)$/, "");
  }
  return undefined;
}
