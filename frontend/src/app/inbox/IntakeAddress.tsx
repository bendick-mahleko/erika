"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";

/** The workspace's intake email address with a copy button (IN-01). */
export function IntakeAddress({ address }: { address: string }) {
  const t = useTranslations("Inbox");
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(address);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <section aria-labelledby="intake-address-label" className="rounded-lg border border-slate-200 bg-brand-50 p-4">
      <h2 id="intake-address-label" className="font-medium">
        {t("addressLabel")}
      </h2>
      <div className="mt-2 flex flex-wrap items-center gap-3">
        <code data-testid="intake-address" className="break-all rounded bg-white px-2 py-1 text-sm">
          {address}
        </code>
        <button
          type="button"
          onClick={copy}
          className="rounded border border-brand-700 px-3 py-1 text-sm font-medium text-brand-700 hover:bg-white"
        >
          {t("copy")}
        </button>
        <span role="status" className="text-sm text-green-800">
          {copied ? t("copied") : ""}
        </span>
      </div>
      <p className="mt-2 text-sm text-slate-700">{t("addressHint")}</p>
    </section>
  );
}
