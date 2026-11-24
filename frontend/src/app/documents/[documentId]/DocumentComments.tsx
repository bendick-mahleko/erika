"use client";

import { useState, type FormEvent } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type Comment } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, SubmitButton } from "@/components/forms";

/** Comments by the team and the tax advisor (EX-02); append-only. */
export function DocumentComments({
  documentId,
  initial,
  canComment,
}: {
  documentId: string;
  initial: Comment[];
  canComment: boolean;
}) {
  const t = useTranslations("Comments");
  const te = useErrorText();
  const format = useFormatter();
  const [comments, setComments] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const body = String(new FormData(formElement).get("comment") ?? "").trim();
    if (!body) return;
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/documents/{documentId}/comments", {
        params: { path: { documentId }, header: { "Idempotency-Key": idempotencyKey() } },
        body: { body },
      });
      if (data) {
        setComments((all) => [...all, data]);
        formElement.reset();
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="comments-title" className="space-y-3">
      <h2 id="comments-title" className="text-xl font-semibold">
        {t("title")}
      </h2>
      {comments.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <ol className="space-y-3" data-testid="comments">
          {comments.map((c) => (
            <li key={c.id} className="rounded border border-slate-200 px-3 py-2">
              <p className="text-sm text-slate-700">
                {c.authorName ?? t("formerMember")} ·{" "}
                {format.dateTime(new Date(c.createdAt), { dateStyle: "medium", timeStyle: "short" })}
              </p>
              <p className="whitespace-pre-wrap break-words">{c.body}</p>
            </li>
          ))}
        </ol>
      )}
      {canComment && (
        <form method="post" onSubmit={submit} className="max-w-2xl space-y-2">
          <ErrorAlert message={error} />
          <label htmlFor="comment" className="block font-medium">
            {t("label")}
          </label>
          <textarea
            id="comment"
            name="comment"
            required
            maxLength={4000}
            rows={3}
            aria-describedby="comment-hint"
            className="block w-full rounded border border-slate-400 px-3 py-2"
          />
          <p id="comment-hint" className="text-sm text-slate-600">
            {t("hint")}
          </p>
          <SubmitButton busy={busy}>{t("submit")}</SubmitButton>
        </form>
      )}
    </section>
  );
}
