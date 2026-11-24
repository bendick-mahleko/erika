"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/** Asks for a reset link; the answer is the same whether or not the address has an account. */
export function ForgotForm() {
  const t = useTranslations("Password");
  const ta = useTranslations("Auth");
  const te = useErrorText();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const email = String(new FormData(event.currentTarget).get("email"));
      const { response, error: problem } = await browserApi.POST("/auth/password-reset", { body: { email } });
      if (response.status === 202) setSent(true);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  if (sent) return <p role="status">{t("sent")}</p>;
  return (
    <form method="post" onSubmit={submit} className="space-y-4">
      <p className="text-slate-700">{t("forgotIntro")}</p>
      <ErrorAlert message={error} />
      <Field id="email" label={ta("email")} type="email" required maxLength={254} autoComplete="username" />
      <SubmitButton busy={busy}>{t("send")}</SubmitButton>
    </form>
  );
}
