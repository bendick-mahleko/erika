import QRCode from "qrcode";
import { getFormatter, getTranslations } from "next-intl/server";
import type { Document } from "@/lib/api/client";
import { CopyButton } from "./CopyButton";

type Transfer = NonNullable<Document["transfer"]>;

/**
 * AR-06: the supplier's bank details with a GiroCode (EPC QR code) that banking apps scan,
 * and copy buttons for each field. The QR code is rendered on the server as SVG.
 */
export async function PaymentPanel({ transfer }: { transfer: Transfer }) {
  const t = await getTranslations("Payment");
  const format = await getFormatter();
  const svg = await QRCode.toString(transfer.epcPayload, { type: "svg", margin: 2, errorCorrectionLevel: "M" });
  const src = `data:image/svg+xml;base64,${Buffer.from(svg).toString("base64")}`;
  const amount =
    transfer.amount != null
      ? format.number(Number(transfer.amount), { style: "currency", currency: transfer.currency })
      : undefined;
  const rows: [string, string | undefined, string | undefined][] = [
    ["payee", transfer.name, transfer.name],
    ["iban", transfer.iban.replace(/(.{4})/g, "$1 ").trim(), transfer.iban],
    ["bic", transfer.bic, transfer.bic],
    ["amount", amount, transfer.amount],
    ["reference", transfer.reference, transfer.reference],
  ];

  return (
    <section aria-labelledby="payment-heading" className="space-y-3 rounded border border-slate-200 p-4">
      <h2 id="payment-heading" className="text-lg font-semibold">
        {t("heading")}
      </h2>
      <div className="flex flex-wrap items-start gap-6">
        <figure className="space-y-1">
          {/* eslint-disable-next-line @next/next/no-img-element -- a data URL, nothing to optimise */}
          <img
            src={src}
            alt={t("qrAlt")}
            width={176}
            height={176}
            className="border border-slate-200"
            data-testid="girocode"
          />
          <figcaption className="max-w-[11rem] text-sm text-slate-600">{t("qrHint")}</figcaption>
        </figure>
        <dl className="grid flex-1 gap-x-4 gap-y-2 sm:grid-cols-[max-content_1fr_auto]">
          {rows
            .filter(([, shown]) => shown)
            .map(([key, shown, copy]) => (
              <div key={key} className="contents">
                <dt className="text-slate-700">{t(key)}</dt>
                <dd className="break-all" data-testid={`transfer-${key}`}>
                  {shown}
                </dd>
                <dd>
                  <CopyButton value={copy ?? ""} label={t("copy", { field: t(key) })} copied={t("copied")} />
                </dd>
              </div>
            ))}
        </dl>
      </div>
    </section>
  );
}
