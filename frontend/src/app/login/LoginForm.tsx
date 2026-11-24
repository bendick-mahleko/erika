"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function LoginForm() {
  const t = useTranslations("Auth");
  const te = useErrorText();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/auth/login", {
        body: { email: String(form.get("email")), password: String(form.get("password")) },
      });
      if (data?.state === "TOTP_ENROLMENT_REQUIRED") router.push("/login/setup");
      else if (data?.state === "TOTP_REQUIRED") router.push("/login/totp");
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form method="post" onSubmit={submit} className="space-y-4">
      <ErrorAlert message={error} />
      <Field id="email" label={t("email")} type="email" required maxLength={254} autoComplete="username" />
      <Field
        id="password"
        label={t("password")}
        type="password"
        required
        maxLength={128}
        autoComplete="current-password"
      />
      <SubmitButton busy={busy}>{t("login.submit")}</SubmitButton>
      <p className="text-sm">
        <Link href="/login/forgot" className="text-brand-700 underline">
          {t("login.forgot")}
        </Link>
      </p>
      <p className="text-sm">
        {t("login.noAccount")}{" "}
        <Link href="/signup" className="text-brand-700 underline">
          {t("signup.title")}
        </Link>
      </p>
    </form>
  );
}
