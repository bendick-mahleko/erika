import { getFormatter, getTranslations } from "next-intl/server";
import type { WorkOrderReport } from "@/lib/api/client";

/** "4.25" as the user reads it ("4,25" in German). */
function decimal(value: string | undefined, locale: string): string {
  if (value === undefined) return "";
  return locale.startsWith("de") ? value.replace(".", ",") : value;
}

/** Hours and minutes between two instants; raw times, rounded only on the invoice (M-BR-04). */
function duration(from: string, to?: string): string | null {
  if (!to) return null;
  const minutes = Math.floor((new Date(to).getTime() - new Date(from).getTime()) / 60000);
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, "0")} h`;
}

/**
 * The job report the technicians recorded in the MARA app (MARA Stage 5, M-MO-03 to M-MO-08):
 * times, materials, Aufmaß, checklist and work description. Read-only in the office.
 */
export async function ReportSection({ report }: { report: WorkOrderReport }) {
  const t = await getTranslations("Reports");
  const format = await getFormatter();
  const locale = t("locale");
  const unit = (code: string) => (t.has(`units.${code}` as "units.C62") ? t(`units.${code}` as "units.C62") : code);
  const time = (iso: string) => format.dateTime(new Date(iso), { timeStyle: "short" });
  const day = (iso: string) => format.dateTime(new Date(iso), { dateStyle: "medium" });
  const dims = (values: (string | undefined)[]) =>
    values
      .filter((v) => v !== undefined)
      .map((v) => decimal(v, locale))
      .join(" × ");
  const summary = report.report;
  return (
    <section aria-labelledby="report" className="space-y-4 rounded border border-slate-300 p-4" data-testid="report">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="report" className="text-lg font-semibold">
          {t("title")}
        </h2>
        {summary && (
          <span
            className={`rounded px-2 py-0.5 text-sm ${summary.state === "COMPLETED" ? "bg-green-100 text-green-900" : "bg-amber-100 text-amber-900"}`}
            data-testid="report-state"
          >
            {t(`states.${summary.state}`)}
          </span>
        )}
      </div>
      {!summary && <p className="text-slate-700">{t("none")}</p>}
      {summary?.signature && (
        <div className="rounded bg-slate-50 p-3 text-sm" data-testid="report-signature">
          <p className="font-medium">
            {summary.signature.kind === "SIGNED"
              ? t("signedBy", {
                  person: summary.signature.signerName ?? "?",
                  at: format.dateTime(new Date(summary.signature.signedAt), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }),
                  version: summary.version,
                })
              : t("sentUnsigned", {
                  reason: summary.signature.notSignedReason ?? "",
                  at: format.dateTime(new Date(summary.signature.signedAt), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }),
                  version: summary.version,
                })}
          </p>
          {summary.signature.comment && <p>{t("customerComment", { comment: summary.signature.comment })}</p>}
          <p className="text-slate-600">
            {t("verificationCode", { code: summary.signature.verificationCode })}
            {summary.signature.emailed && ` · ${t("emailed")}`}
          </p>
          <p className="font-mono text-xs break-all text-slate-600">SHA-256 {summary.signature.contentHash}</p>
          {summary.signature.pdfAvailable ? (
            <a className="text-brand-700 underline" href={`/api/v1/reports/${summary.id}/pdf`} data-testid="report-pdf">
              {t("downloadPdf")}
            </a>
          ) : (
            <p className="text-slate-600">{t("pdfPending")}</p>
          )}
        </div>
      )}
      {report.versions.length > 1 && (
        <p className="text-sm text-slate-700" data-testid="report-versions">
          {t("versions")} {report.versions.map((v) => `${v.version}: ${t(`states.${v.state}`)}`).join(" · ")}
        </p>
      )}
      {summary?.completedAt && (
        <p className="text-sm text-slate-700">
          {t("completedBy", {
            person: summary.completedByName ?? "?",
            at: format.dateTime(new Date(summary.completedAt), { dateStyle: "medium", timeStyle: "short" }),
          })}
        </p>
      )}

      {report.timeEntries.length > 0 && (
        <div>
          <h3 className="font-medium">{t("times")}</h3>
          <table className="mt-1 w-full text-left text-sm" data-testid="report-times">
            <thead>
              <tr className="border-b border-slate-300">
                <th className="py-1 pr-3">{t("who")}</th>
                <th className="py-1 pr-3">{t("kind")}</th>
                <th className="py-1 pr-3">{t("date")}</th>
                <th className="py-1 pr-3">{t("fromTo")}</th>
                <th className="py-1">{t("duration")}</th>
              </tr>
            </thead>
            <tbody>
              {report.timeEntries.map((e) => (
                <tr key={e.id} className="border-b border-slate-200 align-top">
                  <td className="py-1 pr-3">{e.technicianName}</td>
                  <td className="py-1 pr-3">{t(`kinds.${e.kind}`)}</td>
                  <td className="py-1 pr-3">{day(e.startedAt)}</td>
                  <td className="py-1 pr-3">
                    {time(e.startedAt)} – {e.endedAt ? time(e.endedAt) : t("running")}
                    {e.note && <div className="text-slate-600">{e.note}</div>}
                  </td>
                  <td className="py-1">{duration(e.startedAt, e.endedAt) ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {report.materials.length > 0 && (
        <div>
          <h3 className="font-medium">{t("materials")}</h3>
          <ul className="mt-1 divide-y divide-slate-200 text-sm" data-testid="report-materials">
            {report.materials.map((m) => (
              <li key={m.id} className="flex justify-between gap-3 py-1">
                <span>
                  {m.articleNumber && <span className="mr-2 text-slate-600">{m.articleNumber}</span>}
                  {m.description}
                </span>
                <span className="whitespace-nowrap">
                  {decimal(m.quantity, locale)} {unit(m.unitCode)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {report.aufmass.length > 0 && (
        <div>
          <h3 className="font-medium">{t("aufmass")}</h3>
          <ul className="mt-1 divide-y divide-slate-200 text-sm" data-testid="report-aufmass">
            {report.aufmass.map((p) => {
              const count = p.count && p.count !== "1" ? ` × ${decimal(p.count, locale)}` : "";
              const deductions = p.deductions
                .map((d) => [d.label, dims([d.length, d.width, d.height])].filter(Boolean).join(" "))
                .join(", ");
              return (
                <li key={p.id} className="py-1">
                  <div className="flex justify-between gap-3">
                    <span>{p.label}</span>
                    <span className="font-medium whitespace-nowrap">
                      {decimal(p.result, locale)} {unit(p.unitCode)}
                    </span>
                  </div>
                  <div className="text-slate-600">
                    {p.kind === "FORMULA" ? p.expression : `${dims([p.length, p.width, p.height])}${count}`}
                    {deductions && ` ${t("minus")} ${deductions}`}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {report.checklist.length > 0 && (
        <div>
          <h3 className="font-medium">{t("checklist")}</h3>
          <ul className="mt-1 divide-y divide-slate-200 text-sm" data-testid="report-checklist">
            {report.checklist.map((i) => (
              <li key={i.key} className="flex justify-between gap-3 py-1">
                <span>
                  {i.label}
                  {i.required && <span title={t("required")}> *</span>}
                </span>
                <span className={i.value ? "" : "text-slate-500"}>
                  {i.value === "YES" ? t("yes") : i.value === "NO" ? t("no") : (i.value ?? t("open"))}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {report.photos.length > 0 && (
        <div>
          <h3 className="font-medium">{t("photos")}</h3>
          <ul className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4" data-testid="report-photos">
            {report.photos.map((p) => {
              const label = [t(`categories.${p.category}`), p.caption].filter(Boolean).join(": ");
              return (
                <li key={p.id} className="space-y-1 text-sm">
                  {p.state === "STORED" ? (
                    <a href={`/api/v1/photos/${p.id}/file`} title={t("download")}>
                      {/* eslint-disable-next-line @next/next/no-img-element -- served by the API with the session cookie */}
                      <img
                        src={`/api/v1/photos/${p.id}/thumbnail`}
                        alt={label}
                        loading="lazy"
                        className="aspect-[4/3] w-full rounded border border-slate-200 object-cover"
                        data-testid="photo-thumbnail"
                      />
                    </a>
                  ) : (
                    <div
                      className="flex aspect-[4/3] w-full items-center justify-center rounded border border-dashed border-slate-400 bg-slate-50 p-2 text-center text-slate-600"
                      data-testid="photo-placeholder"
                    >
                      {p.state === "REJECTED"
                        ? t(p.rejectReason === "VIRUS" ? "rejectedVirus" : "rejectedNotImage")
                        : t(`states.${p.state}`)}
                    </div>
                  )}
                  <p>{label}</p>
                  <p className="text-slate-600">
                    {p.authorName}, {day(p.takenAt)}
                  </p>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {summary?.workDescription && (
        <div>
          <h3 className="font-medium">{t("workDescription")}</h3>
          <p className="whitespace-pre-line text-slate-800" data-testid="report-description">
            {summary.workDescription}
          </p>
        </div>
      )}
    </section>
  );
}
