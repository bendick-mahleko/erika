"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type Document, type DocumentStatus } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

const STATUSES_IN: DocumentStatus[] = ["NEW", "CHECKED", "APPROVED", "PAID", "DISPUTED", "BOOKED"];
/** Outgoing invoices: users set SENT, PAID, BOOKED; ERIKA sets the others (issue, bounce, cancellation). */
const STATUSES_OUT: DocumentStatus[] = ["SENT", "PAID", "BOOKED"];

/**
 * Status (with a note for the history), tags and note (AR-03), and revalidation. Only
 * metadata changes; the original never does. The history shows who changed what and when.
 */
export function DocumentActions({ doc: initial, canChange }: { doc: Document; canChange: boolean }) {
  const t = useTranslations("Document");
  const te = useErrorText();
  const format = useFormatter();
  const router = useRouter();
  const [doc, setDoc] = useState(initial);
  const [status, setStatus] = useState<DocumentStatus>(initial.status);
  const [statusNote, setStatusNote] = useState("");
  const [tags, setTags] = useState((initial.tags ?? []).join(", "));
  const [note, setNote] = useState(initial.note ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const base = doc.direction === "OUT" ? STATUSES_OUT : STATUSES_IN;
  // The current status stays selectable even if it is one ERIKA set (e.g. ISSUED).
  const statuses = base.includes(doc.status) ? base : [doc.status, ...base];

  async function save(
    body: { status?: DocumentStatus; statusNote?: string; tags?: string[]; note?: string },
    done: string,
  ) {
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      const { data, error: problem } = await browserApi.PATCH("/documents/{documentId}", {
        params: { path: { documentId: doc.id } },
        body,
      });
      if (data) {
        setDoc(data);
        setStatusNote("");
        setMessage(done);
        router.refresh();
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function revalidate() {
    setBusy(true);
    setError(null);
    const { data, error: problem } = await browserApi.POST("/documents/{documentId}/revalidate", {
      params: { path: { documentId: doc.id } },
    });
    setBusy(false);
    if (data) {
      setDoc(data);
      setMessage(t("revalidateQueued"));
      router.refresh();
    } else {
      setError(te(problemCode(problem)));
    }
  }

  const history = doc.history ?? [];

  return (
    <section aria-labelledby="status-heading" className="space-y-4 rounded-lg border border-slate-200 p-4">
      <h2 id="status-heading" className="text-lg font-semibold">
        {t("statusHeading")}
      </h2>
      <p>
        {t("currentStatus")}: <strong data-testid="current-status">{t(`status.${doc.status}`)}</strong>
      </p>
      <ErrorAlert message={error} />
      {canChange && (
        <form
          className="grid gap-3 sm:grid-cols-[max-content_1fr_max-content] sm:items-end"
          onSubmit={(e) => {
            e.preventDefault();
            void save({ status, statusNote: statusNote.trim() || undefined }, t("statusSaved"));
          }}
        >
          <div>
            <label htmlFor="new-status" className="block font-medium">
              {t("newStatus")}
            </label>
            <select
              id="new-status"
              value={status}
              onChange={(e) => setStatus(e.target.value as DocumentStatus)}
              className="rounded border border-slate-400 px-2 py-2"
            >
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {t(`status.${s}`)}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="status-note" className="block font-medium">
              {t("statusNote")}
            </label>
            <input
              id="status-note"
              value={statusNote}
              maxLength={500}
              onChange={(e) => setStatusNote(e.target.value)}
              className="block w-full rounded border border-slate-400 px-3 py-2"
            />
          </div>
          <button
            type="submit"
            disabled={busy || status === doc.status}
            className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-50"
          >
            {t("changeStatus")}
          </button>
        </form>
      )}

      <div>
        <h3 className="font-medium">{t("history")}</h3>
        {history.length === 0 ? (
          <p className="text-sm text-slate-600">{t("noHistory")}</p>
        ) : (
          <ol className="mt-1 space-y-1 text-sm" data-testid="status-history">
            {history.map((h, i) => (
              <li key={i}>
                {t("historyEntry", {
                  at: format.dateTime(new Date(h.at), { dateStyle: "medium", timeStyle: "short" }),
                  user: h.userName ?? t("unknownUser"),
                  from: h.fromStatus ? t(`status.${h.fromStatus}`) : "–",
                  to: t(`status.${h.toStatus}`),
                })}
                {h.note ? ` – „${h.note}“` : ""}
              </li>
            ))}
          </ol>
        )}
      </div>

      <form
        className="grid gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          void save(
            {
              tags: tags
                .split(",")
                .map((x) => x.trim())
                .filter(Boolean),
              note,
            },
            t("metadataSaved"),
          );
        }}
      >
        <fieldset disabled={!canChange || busy} className="grid gap-3">
          <div>
            <label htmlFor="tags" className="block font-medium">
              {t("tags")}
            </label>
            <input
              id="tags"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              aria-describedby="tags-hint"
              className="block w-full rounded border border-slate-400 px-3 py-2"
            />
            <p id="tags-hint" className="text-sm text-slate-600">
              {t("tagsHint")}
            </p>
          </div>
          <div>
            <label htmlFor="note" className="block font-medium">
              {t("note")}
            </label>
            <textarea
              id="note"
              value={note}
              maxLength={2000}
              rows={3}
              onChange={(e) => setNote(e.target.value)}
              className="block w-full rounded border border-slate-400 px-3 py-2"
            />
          </div>
        </fieldset>
        {canChange && (
          <div className="flex flex-wrap gap-3">
            <button
              type="submit"
              disabled={busy}
              className="rounded border border-brand-700 px-4 py-2 font-medium text-brand-700 disabled:opacity-50"
            >
              {t("saveMetadata")}
            </button>
            <button
              type="button"
              disabled={busy || doc.processing === "PENDING"}
              onClick={revalidate}
              className="rounded border border-slate-400 px-4 py-2 disabled:opacity-50"
            >
              {t("revalidate")}
            </button>
          </div>
        )}
      </form>
      <p role="status" className="text-green-800">
        {message}
      </p>
    </section>
  );
}
