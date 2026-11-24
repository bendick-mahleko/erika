"use client";

import { useRef, useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, SubmitButton } from "@/components/forms";

const MAX_BYTES = 512 * 1024;

/** CR-08: the company logo for the readable PDF of ZUGFeRD invoices (PNG or JPEG, at most 512 KB). */
export function LogoSettings({ initialHasLogo, canEdit }: { initialHasLogo: boolean; canEdit: boolean }) {
  const t = useTranslations("InvoiceSettings");
  const te = useErrorText();
  const input = useRef<HTMLInputElement>(null);
  const [hasLogo, setHasLogo] = useState(initialHasLogo);
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const file = input.current?.files?.[0];
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setError(te("LOGO_TOO_LARGE"));
      return;
    }
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const body = new FormData();
      body.append("file", file);
      const { error: problem, response } = await browserApi.PUT("/invoice-settings/logo", {
        // The generated type describes the form field; the browser sends the FormData as is.
        body: body as unknown as { file: string },
        bodySerializer: (b) => b as unknown as FormData,
      });
      if (response.ok) {
        setHasLogo(true);
        setVersion((v) => v + 1);
        setStatus(t("logoSaved"));
        event.currentTarget?.reset();
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const { error: problem, response } = await browserApi.DELETE("/invoice-settings/logo");
      if (response.ok) {
        setHasLogo(false);
        setStatus(t("logoRemoved"));
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="logo-heading" className="max-w-2xl space-y-3 rounded border border-slate-200 p-4">
      <h2 id="logo-heading" className="font-semibold">
        {t("logoHeading")}
      </h2>
      <p className="text-sm text-slate-600">{t("logoHint")}</p>
      <ErrorAlert message={error} />
      {hasLogo ? (
        // eslint-disable-next-line @next/next/no-img-element -- served by the API with the session cookie
        <img
          src={`/api/v1/invoice-settings/logo?v=${version}`}
          alt={t("logoAlt")}
          className="max-h-16 max-w-[16rem] border border-slate-200 bg-white p-1"
          data-testid="logo-preview"
        />
      ) : (
        <p className="text-slate-700">{t("logoNone")}</p>
      )}
      {canEdit && (
        <form method="post" onSubmit={upload} className="flex flex-wrap items-end gap-3">
          <div className="space-y-1">
            <label htmlFor="logoFile" className="block font-medium">
              {t("logoFile")}
            </label>
            <input
              ref={input}
              id="logoFile"
              name="file"
              type="file"
              accept="image/png,image/jpeg"
              required
              disabled={busy}
              className="block"
            />
          </div>
          <SubmitButton busy={busy}>{t("logoUpload")}</SubmitButton>
          {hasLogo && (
            <button
              type="button"
              onClick={remove}
              disabled={busy}
              className="rounded border border-red-700 px-4 py-2 text-red-800 disabled:opacity-60"
            >
              {t("logoRemove")}
            </button>
          )}
        </form>
      )}
      <p role="status" className="text-green-800">
        {status}
      </p>
    </section>
  );
}
