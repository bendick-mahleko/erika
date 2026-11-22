"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function TotpForm() {
  const t = useTranslations("Auth");
  const te = useErrorText();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recovery, setRecovery] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = String(new FormData(event.currentTarget).get("code")).trim();
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/auth/totp", {
        body: recovery ? { recoveryCode: value } : { code: value.replace(/\s/g, "") },
      });
      if (data?.state === "AUTHENTICATED") {
        router.push("/settings/workspace");
        router.refresh();
      } else {
        const code = problemCode(problem);
        if (code === "LOGIN_REQUIRED") router.push("/login");
        else setError(te(code));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <p className="text-slate-700">{t("totp.intro")}</p>
      <ErrorAlert message={error} />
      {recovery ? (
        <Field key="recovery" id="code" label={t("totp.recoveryCode")} required maxLength={20} autoComplete="off" />
      ) : (
        <Field
          key="totp"
          id="code"
          label={t("totp.code")}
          required
          inputMode="numeric"
          pattern="[0-9 ]{6,7}"
          maxLength={7}
          autoComplete="one-time-code"
          autoFocus
        />
      )}
      <SubmitButton busy={busy}>{t("totp.submit")}</SubmitButton>
      <p className="text-sm">
        <button type="button" className="text-brand-700 underline" onClick={() => setRecovery(!recovery)}>
          {recovery ? t("totp.useApp") : t("totp.useRecovery")}
        </button>
      </p>
    </form>
  );
}
