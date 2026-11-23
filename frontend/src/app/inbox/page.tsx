import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchDocuments, fetchIntakeAddress, fetchSession, type DocumentFilter } from "@/lib/api/client";
import { InboxFilters } from "./InboxFilters";
import { IntakeAddress } from "./IntakeAddress";
import { InboxClient } from "./InboxClient";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Inbox");
  return { title: `${t("title")} – ERIKA` };
}

export const dynamic = "force-dynamic";

const STATUSES = ["NEW", "CHECKED", "APPROVED", "PAID", "DISPUTED", "BOOKED"] as const;

/** Filters from the URL (?q=…&status=…&from=…&to=…&tag=…), so a filtered list can be bookmarked. */
function filterFrom(params: Record<string, string | string[] | undefined>): DocumentFilter {
  const one = (key: string) => {
    const v = params[key];
    const s = (Array.isArray(v) ? v[0] : v)?.trim();
    return s ? s : undefined;
  };
  const date = (key: string) => {
    const v = one(key);
    return v && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : undefined;
  };
  const status = one("status");
  const filter: DocumentFilter = {
    q: one("q")?.slice(0, 200),
    status: STATUSES.find((s) => s === status),
    from: date("from"),
    to: date("to"),
    tag: one("tag")?.slice(0, 50),
  };
  return Object.fromEntries(Object.entries(filter).filter(([, v]) => v !== undefined)) as DocumentFilter;
}

export default async function InboxPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  const filter = filterFrom(await searchParams);
  const [documents, address] = await Promise.all([fetchDocuments(filter), fetchIntakeAddress()]);
  const t = await getTranslations("Inbox");

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">{t("title")}</h1>
        <p className="mt-2 max-w-3xl text-slate-700">{t("intro")}</p>
      </div>
      {address && <IntakeAddress address={address} />}
      {/* Tax advisors only read (EX-02); the back end refuses uploads from them anyway. */}
      <InboxFilters filter={filter} />
      <InboxClient
        key={JSON.stringify(filter)}
        filter={filter}
        initial={documents?.items ?? []}
        nextCursor={documents?.nextCursor}
        canUpload={session.role !== "ADVISOR"}
      />
    </div>
  );
}
