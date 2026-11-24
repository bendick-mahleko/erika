"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function ChangePasswordForm() {
  const t = useTranslations("Password");
  const ta = useTranslations("Auth");
  const te = useErrorText();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const { response, error: problem } = await browserApi.POST("/auth/password", {
        body: { currentPassword: String(form.get("currentPassword")), newPassword: String(form.get("newPassword")) },
      });
      if (response.status === 204) {
        setSaved(true);
        formElement.reset();
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form method="post" onSubmit={submit} className="max-w-md space-y-4">
      <h2 className="text-xl font-semibold">{t("changeTitle")}</h2>
      <ErrorAlert message={error} />
      <Field
        id="currentPassword"
        label={t("currentPassword")}
        type="password"
        required
        maxLength={128}
        autoComplete="current-password"
      />
      <Field
        id="newPassword"
        label={t("newPassword")}
        hint={ta("passwordHint")}
        type="password"
        required
        minLength={12}
        maxLength={128}
        autoComplete="new-password"
      />
      <SubmitButton busy={busy}>{t("save")}</SubmitButton>
      <p role="status" className="text-green-800">
        {saved ? t("changed") : ""}
      </p>
    </form>
  );
}
