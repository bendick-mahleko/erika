import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getFormatter, getTranslations } from "next-intl/server";
import { fetchBilling, fetchBillingInvoices, fetchMaraAddon, fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { BillingPanel } from "./BillingPanel";
import { MaraAddonSection } from "./MaraAddonSection";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Billing");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function BillingPage({
  searchParams,
}: {
  searchParams: Promise<{ checkout?: string | string[] }>;
}) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const [status, invoices, mara] = await Promise.all([
    fetchBilling(),
    fetchBillingInvoices(),
    session.features?.mara ? fetchMaraAddon() : Promise.resolve(null),
  ]);
  if (!status) redirect("/login");
  const raw = (await searchParams).checkout;
  const checkout = Array.isArray(raw) ? raw[0] : raw;
  const t = await getTranslations("Billing");
  const format = await getFormatter();
  const day = (iso?: string) => (iso ? format.dateTime(new Date(`${iso}T00:00:00`), { dateStyle: "medium" }) : "");

  return (
    <div className="space-y-6">
      <SettingsNav current="billing" />
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {checkout === "success" && (
        <p role="status" className="text-green-800">
          {t("success")}
        </p>
      )}
      {checkout === "cancelled" && <p role="status">{t("cancelled")}</p>}
      <BillingPanel status={status} isOwner={session.role === "OWNER"} />
      {mara && <MaraAddonSection initial={mara} billing={status} isOwner={session.role === "OWNER"} />}
      {status.enabled && status.state !== "EXEMPT" && (
        <section aria-labelledby="invoices-title" className="space-y-3">
          <h2 id="invoices-title" className="text-xl font-semibold">
            {t("invoices")}
          </h2>
          <p className="text-slate-700">{t("invoiceCopy")}</p>
          {invoices.length === 0 ? (
            <p className="text-slate-700">{t("noInvoices")}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm" data-testid="billing-invoices">
                <thead className="border-b border-slate-300">
                  <tr>
                    <th scope="col" className="py-2 pr-4">
                      {t("number")}
                    </th>
                    <th scope="col" className="py-2 pr-4">
                      {t("date")}
                    </th>
                    <th scope="col" className="py-2 pr-4">
                      {t("period")}
                    </th>
                    <th scope="col" className="py-2 pr-4 text-right">
                      {t("amount")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((i) => (
                    <tr key={i.id} className="border-b border-slate-100">
                      <td className="py-2 pr-4">{i.number ?? t(`invoiceStates.${i.state}`)}</td>
                      <td className="py-2 pr-4">{day(i.issueDate)}</td>
                      <td className="py-2 pr-4">
                        {i.periodStart ? `${day(i.periodStart)} – ${day(i.periodEnd)}` : ""}
                      </td>
                      <td className="py-2 pr-4 text-right">
                        {format.number(Number(i.grossAmount), { style: "currency", currency: i.currency })}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
