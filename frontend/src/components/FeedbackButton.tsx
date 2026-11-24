"use client";

import { useRef, useState, type FormEvent } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "./forms";

const CATEGORIES = ["PROBLEM", "IDEA", "PRAISE", "OTHER"] as const;

/**
 * In-app feedback for the closed beta (ADR 0030): a modal dialog (native <dialog>, so
 * focus stays inside and Escape closes it). Sends the current path, never the query string.
 */
export function FeedbackButton() {
  const t = useTranslations("Feedback");
  const te = useErrorText();
  const pathname = usePathname();
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  function open() {
    setSent(false);
    setError(null);
    dialog.current?.showModal();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setBusy(true);
    setError(null);
    try {
      const { response, error: problem } = await browserApi.POST("/feedback", {
        body: {
          category: String(form.get("category")) as (typeof CATEGORIES)[number],
          message: String(form.get("message")),
          page: pathname,
        },
      });
      if (response.status === 202) {
        setSent(true);
        formElement.reset();
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={open}
        className="rounded border border-white px-2 py-1 text-white hover:bg-white hover:text-brand-900"
        data-testid="feedback-button"
      >
        {t("button")}
      </button>
      <dialog
        ref={dialog}
        aria-labelledby="feedback-title"
        className="w-[min(36rem,calc(100vw-2rem))] rounded-lg p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/50"
      >
        <div className="space-y-4 p-6">
          <h2 id="feedback-title" className="text-xl font-semibold">
            {t("title")}
          </h2>
          {sent ? (
            <>
              <p role="status" data-testid="feedback-thanks">
                {t("thanks")}
              </p>
              <button
                type="button"
                onClick={() => dialog.current?.close()}
                className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
              >
                {t("close")}
              </button>
            </>
          ) : (
            <form method="post" onSubmit={submit} className="space-y-4">
              <p className="text-sm text-slate-700">{t("intro")}</p>
              <ErrorAlert message={error} />
              <fieldset className="space-y-1">
                <legend className="font-medium">{t("category")}</legend>
                <div className="flex flex-wrap gap-x-4 gap-y-1">
                  {CATEGORIES.map((c, i) => (
                    <label key={c} className="flex items-center gap-1">
                      <input type="radio" name="category" value={c} defaultChecked={i === 0} required />
                      {t(`categories.${c}`)}
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="space-y-1">
                <label htmlFor="feedback-message" className="block font-medium">
                  {t("message")}
                </label>
                <textarea
                  id="feedback-message"
                  name="message"
                  required
                  maxLength={4000}
                  rows={6}
                  className="block w-full rounded border border-slate-400 px-3 py-2"
                />
              </div>
              <div className="flex flex-wrap gap-3">
                <button
                  type="submit"
                  disabled={busy}
                  aria-busy={busy}
                  className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60"
                >
                  {t("send")}
                </button>
                <button
                  type="button"
                  onClick={() => dialog.current?.close()}
                  className="rounded border border-slate-400 px-4 py-2"
                >
                  {t("cancel")}
                </button>
              </div>
            </form>
          )}
        </div>
      </dialog>
    </>
  );
}
