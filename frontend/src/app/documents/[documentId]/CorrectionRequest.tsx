"use client";

import { useState } from "react";
import { useFormatter, useTranslations } from "next-intl";

/**
 * "Request a corrected invoice" for invoices with errors: a prepared email text listing the
 * reasons in plain German, to copy or open in the mail program. ERIKA doesn't send it itself.
 */
export function CorrectionRequest({
  sellerEmail,
  sellerName,
  invoiceNumber,
  issueDate,
  errors,
}: {
  sellerEmail?: string;
  sellerName?: string;
  invoiceNumber?: string;
  issueDate?: string;
  errors: string[];
}) {
  const t = useTranslations("Correction");
  const format = useFormatter();
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const date = issueDate ? format.dateTime(new Date(issueDate), { dateStyle: "medium" }) : "";
  const subject = t("subject", { number: invoiceNumber ?? "–" });
  const body = t("body", {
    seller: sellerName ?? t("defaultSeller"),
    number: invoiceNumber ?? "–",
    date: date || "–",
    reasons: errors.map((e) => `- ${e}`).join("\n"),
  });
  const mailto = `mailto:${sellerEmail ?? ""}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;

  async function copy() {
    try {
      await navigator.clipboard.writeText(`${subject}\n\n${body}`);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="rounded bg-white p-3 text-slate-900">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="correction-text"
        onClick={() => setOpen(!open)}
        className="rounded bg-red-800 px-3 py-1 font-medium text-white hover:bg-red-900"
      >
        {t("button")}
      </button>
      {open && (
        <div id="correction-text" className="mt-3 space-y-2">
          <p className="text-sm">{t("intro")}</p>
          <label htmlFor="correction-body" className="block font-medium">
            {t("textLabel")}
          </label>
          <textarea
            id="correction-body"
            readOnly
            rows={10}
            value={`${subject}\n\n${body}`}
            className="block w-full rounded border border-slate-400 px-3 py-2 font-mono text-sm"
          />
          <div className="flex flex-wrap items-center gap-3">
            <a href={mailto} className="rounded border border-brand-700 px-3 py-1 text-brand-700">
              {t("openMail")}
            </a>
            <button type="button" onClick={copy} className="rounded border border-slate-400 px-3 py-1">
              {t("copy")}
            </button>
            <span role="status" className="text-sm text-green-800">
              {copied ? t("copied") : ""}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
