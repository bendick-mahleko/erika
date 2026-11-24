import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getFormatter, getTranslations } from "next-intl/server";
import { fetchInvoices, fetchSession } from "@/lib/api/client";
import { NewInvoiceButton } from "./NewInvoiceButton";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Creator");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Outgoing invoices and drafts (CR-01 … CR-07). */
export default async function InvoicesPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const list = await fetchInvoices();
  const t = await getTranslations("Creator");
  const td = await getTranslations("Document");
  const format = await getFormatter();
  const items = list?.items ?? [];
  const link = "text-brand-700 underline underline-offset-4";

  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="max-w-3xl text-slate-700">{t("intro")}</p>
        <nav aria-label={t("title")} className="flex flex-wrap items-center gap-4">
          {session.role !== "ADVISOR" && <NewInvoiceButton />}
          <Link href="/customers" className={link}>
            {t("customersLink")}
          </Link>
          <Link href="/articles" className={link}>
            {t("articlesLink")}
          </Link>
          <Link href="/invoices/recurring" className={link}>
            {t("recurringLink")}
          </Link>
          <Link href="/settings/invoices" className={link}>
            {t("settingsLink")}
          </Link>
        </nav>
      </div>

      <section aria-labelledby="invoices-heading" className="space-y-3">
        <h2 id="invoices-heading" className="text-lg font-semibold">
          {t("listHeading")}
        </h2>
        {items.length === 0 ? (
          <p className="text-slate-700">{t("empty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[40rem] text-left text-sm" data-testid="invoice-list">
              <thead className="border-b border-slate-300">
                <tr>
                  <th scope="col" className="py-2 pr-3">
                    {t("column.number")}
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    {t("column.customer")}
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    {t("column.date")}
                  </th>
                  <th scope="col" className="py-2 pr-3 text-right">
                    {t("column.amount")}
                  </th>
                  <th scope="col" className="py-2">
                    {t("column.state")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id} className="border-b border-slate-200">
                    <td className="py-2 pr-3">
                      <Link href={`/invoices/${item.id}`} className={link}>
                        {item.number ?? t("noNumber")}
                      </Link>
                      <span className="ml-2 text-slate-600">{t(`kind.${item.kind}`)}</span>
                    </td>
                    <td className="py-2 pr-3">{item.buyerName ?? "–"}</td>
                    <td className="py-2 pr-3">
                      {item.issueDate ? format.dateTime(new Date(item.issueDate), { dateStyle: "medium" }) : "–"}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {item.grossAmount
                        ? format.number(Number(item.grossAmount), {
                            style: "currency",
                            currency: item.currency ?? "EUR",
                          })
                        : "–"}
                    </td>
                    <td className="py-2">
                      {item.state === "DRAFT"
                        ? t("draftLabel")
                        : item.state === "ISSUING"
                          ? t("issuingLabel")
                          : item.status
                            ? td(`status.${item.status}`)
                            : ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
