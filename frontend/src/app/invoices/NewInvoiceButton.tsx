"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";

/** Creates an empty draft (workspace defaults) and opens it. */
export function NewInvoiceButton() {
  const t = useTranslations("Creator");
  const te = useErrorText();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/invoices", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: { lines: [{ quantity: "1", unitCode: "C62", vatRate: "STANDARD_19" }] },
      });
      if (data) router.push(`/invoices/${data.id}`);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={create}
        disabled={busy}
        aria-busy={busy}
        className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60"
        data-testid="new-invoice"
      >
        {t("new")}
      </button>
      {error && (
        <p role="alert" className="text-red-800">
          {error}
        </p>
      )}
    </>
  );
}
