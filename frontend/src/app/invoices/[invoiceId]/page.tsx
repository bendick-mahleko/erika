import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchArticles, fetchCustomers, fetchInvoice, fetchInvoiceSettings, fetchSession } from "@/lib/api/client";
import { InvoiceEditor } from "./InvoiceEditor";
import { IssuedInvoice } from "./IssuedInvoice";
import { IssuingWatcher } from "./IssuingWatcher";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Creator");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** One invoice: the editable draft, the progress while it is issued, or the issued invoice. */
export default async function InvoicePage({ params }: { params: Promise<{ invoiceId: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const { invoiceId } = await params;
  const result = await fetchInvoice(invoiceId);
  if (!result) notFound();
  const { invoice, etag } = result;
  const t = await getTranslations("Creator");
  const te = await getTranslations("Editor");
  const canEdit = session.role !== "ADVISOR";

  return (
    <div className="space-y-6">
      <p>
        <Link href="/invoices" className="text-brand-700 underline">
          {t("back")}
        </Link>
      </p>
      {invoice.state === "DRAFT" && (
        <InvoiceEditor
          key={etag}
          initial={invoice}
          etag={etag}
          settings={(await fetchInvoiceSettings())?.settings ?? null}
          customers={await fetchCustomers()}
          articles={await fetchArticles()}
          canEdit={canEdit}
        />
      )}
      {invoice.state === "ISSUING" && (
        <div className="space-y-3">
          <h1 className="text-2xl font-semibold">{t(`kind.${invoice.kind}`)}</h1>
          <IssuingWatcher message={te("issuing")} />
        </div>
      )}
      {invoice.state === "ISSUED" && <IssuedInvoice invoice={invoice} canEdit={canEdit} />}
    </div>
  );
}
