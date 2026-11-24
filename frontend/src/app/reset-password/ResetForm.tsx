"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function ResetForm({ token }: { token: string }) {
  const t = useTranslations("Password");
  const ta = useTranslations("Auth");
  const te = useErrorText();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const password = String(new FormData(event.currentTarget).get("password"));
      const { response, error: problem } = await browserApi.POST("/auth/password-reset/confirm", {
        body: { token, password },
      });
      if (response.status === 204) setDone(true);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div role="status" className="space-y-3">
        <p>{t("resetDone")}</p>
        <Link href="/login" className="text-brand-700 underline">
          {ta("login.title")}
        </Link>
      </div>
    );
  }
  return (
    <form method="post" onSubmit={submit} className="space-y-4">
      <ErrorAlert message={error} />
      <Field
        id="password"
        label={t("newPassword")}
        hint={ta("passwordHint")}
        type="password"
        required
        minLength={12}
        maxLength={128}
        autoComplete="new-password"
      />
      <SubmitButton busy={busy}>{t("save")}</SubmitButton>
    </form>
  );
}
