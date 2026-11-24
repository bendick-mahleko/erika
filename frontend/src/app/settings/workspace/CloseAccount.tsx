"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type Workspace } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field } from "@/components/forms";

/** Account closure (EX-04, owners): the workspace becomes read-only and is kept until the last retention date. */
export function CloseAccount({ workspace }: { workspace: Workspace }) {
  const t = useTranslations("Closure");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (workspace.closedAt) {
    return (
      <p role="status" data-testid="closed-info">
        {t("closed", {
          date: format.dateTime(new Date(workspace.closedAt), { dateStyle: "medium" }),
          deleteAfter: workspace.deleteAfter
            ? format.dateTime(new Date(`${workspace.deleteAfter}T00:00:00`), { dateStyle: "medium" })
            : "–",
        })}
      </p>
    );
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const confirmName = String(new FormData(event.currentTarget).get("confirmName") ?? "");
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/workspace/closure", { body: { confirmName } });
      if (data) router.refresh();
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="closure-title" className="space-y-3 rounded border border-red-700 p-4">
      <h2 id="closure-title" className="text-xl font-semibold text-red-900">
        {t("title")}
      </h2>
      <p className="max-w-3xl">{t("intro")}</p>
      <p>
        <Link href="/exports" className="text-brand-700 underline">
          {t("exportFirst")}
        </Link>
      </p>
      <form method="post" onSubmit={submit} className="max-w-xl space-y-3">
        <ErrorAlert message={error} />
        <Field id="confirmName" label={t("confirm", { name: workspace.name })} required autoComplete="off" />
        <button
          type="submit"
          disabled={busy}
          aria-busy={busy}
          className="rounded bg-red-800 px-4 py-2 font-medium text-white hover:bg-red-900 disabled:opacity-60"
        >
          {t("submit")}
        </button>
      </form>
    </section>
  );
}
