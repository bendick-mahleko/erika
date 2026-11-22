"use client";

import { useState, type FormEvent } from "react";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function SignupForm() {
  const t = useTranslations("Auth");
  const te = useErrorText();
  const locale = useLocale();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const email = String(form.get("email"));
      const { error: problem, response } = await browserApi.POST("/auth/signup", {
        body: {
          email,
          password: String(form.get("password")),
          displayName: String(form.get("displayName")),
          workspaceName: String(form.get("workspaceName")),
          locale: locale === "en" ? "en" : "de",
        },
      });
      if (response.status === 202) setSentTo(email);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  if (sentTo) {
    return (
      <div role="status" className="space-y-2">
        <h2 className="text-lg font-semibold">{t("signup.doneTitle")}</h2>
        <p>{t("signup.done", { email: sentTo })}</p>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="space-y-4" noValidate={false}>
      <p className="text-slate-700">{t("signup.intro")}</p>
      <ErrorAlert message={error} />
      <Field id="displayName" label={t("displayName")} required maxLength={100} autoComplete="name" />
      <Field id="workspaceName" label={t("workspaceName")} required maxLength={200} autoComplete="organization" />
      <Field id="email" label={t("email")} type="email" required maxLength={254} autoComplete="email" />
      <Field
        id="password"
        label={t("password")}
        hint={t("passwordHint")}
        type="password"
        required
        minLength={12}
        maxLength={128}
        autoComplete="new-password"
      />
      <SubmitButton busy={busy}>{t("signup.submit")}</SubmitButton>
      <p className="text-sm">
        {t("signup.haveAccount")}{" "}
        <Link href="/login" className="text-brand-700 underline">
          {t("login.title")}
        </Link>
      </p>
    </form>
  );
}
