import type { Metadata } from "next";
import { getFormatter, getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { fetchReportVerification } from "@/lib/api/client";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Verify");
  return { title: `${t("title")} – ERIKA`, robots: { index: false, follow: false } };
}

/**
 * The public check of a signed job report (MARA, M-MO-11): reached through the QR code or the
 * code on the PDF, without signing in. Shows hash and status, never the report's content; with
 * the key from the QR code, the customer downloads the PDF.
 */
export default async function VerifyPage({
  params,
  searchParams,
}: {
  params: Promise<{ code: string }>;
  searchParams: Promise<{ k?: string | string[] }>;
}) {
  const t = await getTranslations("Verify");
  const format = await getFormatter();
  const { code } = await params;
  const raw = (await searchParams).k;
  const key = Array.isArray(raw) ? raw[0] : raw;
  const valid = /^[A-Z2-7]{10}$/.test(code);
  const verification = valid ? await fetchReportVerification(code) : null;
  return (
    <AuthCard title={t("title")}>
      {!verification ? (
        <p role="alert" data-testid="verify-unknown">
          {t("unknown")}
        </p>
      ) : (
        <div className="space-y-3" data-testid="verification">
          <p className="text-lg font-medium" data-testid="verify-status">
            {t(verification.kind === "SIGNED" ? "signed" : "notSigned", {
              firm: verification.firmName,
              version: verification.reportVersion,
              date: format.dateTime(new Date(verification.signedAt), { dateStyle: "long", timeStyle: "short" }),
            })}
          </p>
          {verification.superseded && (
            <p role="status" className="rounded bg-amber-100 p-2 text-amber-900">
              {t("superseded")}
            </p>
          )}
          <p className="text-sm text-slate-700">{t("hashIntro")}</p>
          <p className="font-mono text-xs break-all" data-testid="verify-hash">
            {verification.contentHash}
          </p>
          {key && verification.pdfArchived && (
            <p>
              <a
                className="text-brand-700 underline"
                href={`/api/v1/public/report-verifications/${code}/pdf?key=${encodeURIComponent(key)}`}
                data-testid="verify-download"
              >
                {t("download")}
              </a>
            </p>
          )}
          {key && !verification.pdfArchived && <p className="text-sm text-slate-700">{t("pdfPending")}</p>}
        </div>
      )}
    </AuthCard>
  );
}
