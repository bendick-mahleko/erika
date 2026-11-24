"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import QRCode from "qrcode";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

type Enrolment = { secret: string; qrSvg: string };

export function TotpSetup() {
  const t = useTranslations("Auth");
  const te = useErrorText();
  const router = useRouter();
  const [enrolment, setEnrolment] = useState<Enrolment | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    (async () => {
      const { data, error: problem } = await browserApi.POST("/auth/totp/enrolment");
      if (!data) {
        const code = problemCode(problem);
        if (code === "LOGIN_REQUIRED") router.push("/login");
        else setError(te(code));
        return;
      }
      // The SVG is generated locally from our own otpauth URI; the secret never leaves the browser.
      const qrSvg = await QRCode.toString(data.otpauthUri, { type: "svg", margin: 1, errorCorrectionLevel: "M" });
      setEnrolment({ secret: data.secret, qrSvg });
    })().catch(() => setError(te("generic")));
  }, [router, te]);

  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get("code")).replace(/\s/g, "");
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/auth/totp/enrolment/confirm", { body: { code } });
      if (data) setCodes(data.codes);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  if (codes) {
    return (
      <div className="space-y-4">
        <h2 className="text-lg font-semibold">{t("setup.codesTitle")}</h2>
        <p>{t("setup.codesIntro")}</p>
        <ul className="grid grid-cols-2 gap-2 font-mono text-lg" data-testid="recovery-codes">
          {codes.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
        <button
          type="button"
          className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
          onClick={() => {
            router.push("/settings/workspace");
            router.refresh();
          }}
        >
          {t("setup.continue")}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-slate-700">{t("setup.intro")}</p>
      <ErrorAlert message={error} />
      {enrolment && (
        <>
          <div
            role="img"
            aria-label={t("setup.qrAlt")}
            className="mx-auto w-48"
            dangerouslySetInnerHTML={{ __html: enrolment.qrSvg }}
          />
          <p className="text-sm">
            {t("setup.manual")}{" "}
            <code className="break-all rounded bg-slate-100 px-1" data-testid="totp-secret">
              {enrolment.secret}
            </code>
          </p>
          <form method="post" onSubmit={confirm} className="space-y-4">
            <Field
              id="code"
              label={t("setup.code")}
              required
              inputMode="numeric"
              pattern="[0-9 ]{6,7}"
              maxLength={7}
              autoComplete="one-time-code"
            />
            <SubmitButton busy={busy}>{t("setup.submit")}</SubmitButton>
          </form>
        </>
      )}
    </div>
  );
}
