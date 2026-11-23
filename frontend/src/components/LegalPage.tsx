import { getFormatter, getTranslations } from "next-intl/server";
import type { LegalDocument } from "@/content/legal/types";
import { Markdown } from "./Markdown";

/** A legal document (German only; the German text is binding). */
export async function LegalPage({ doc }: { doc: LegalDocument }) {
  const t = await getTranslations("Legal");
  const format = await getFormatter();
  const germanOnly = t("germanOnly");
  return (
    <article lang="de" className="max-w-3xl">
      <h1 className="text-2xl font-semibold">{doc.title}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {t("updated", { date: format.dateTime(new Date(doc.updated), { dateStyle: "long" }) })}
      </p>
      {doc.draft && (
        <p className="mt-4 rounded border border-amber-700 bg-amber-50 px-4 py-2 text-amber-900" role="note">
          {t("draft")}
        </p>
      )}
      {germanOnly && (
        <p lang="en" className="mt-4 text-sm text-slate-600">
          {germanOnly}
        </p>
      )}
      <Markdown source={doc.body} />
    </article>
  );
}
