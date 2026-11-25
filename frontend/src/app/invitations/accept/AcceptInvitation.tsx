"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { browserApi, problemCode, type InvitationPreview } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/**
 * A workspace invitation link (EX-02): joins with the signed-in account if it is the invited
 * address, else points to sign-in or sign-up.
 */
export function AcceptInvitation({ token, signedInEmail }: { token: string; signedInEmail?: string }) {
  const t = useTranslations("Invitation");
  const te = useErrorText();
  const router = useRouter();
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [invalid, setInvalid] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    browserApi
      .POST("/auth/invitation", { body: { token } })
      .then(({ data }) => (data ? setPreview(data) : setInvalid(true)))
      .catch(() => setInvalid(true));
  }, [token]);

  async function accept() {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/auth/invitation/accept", { body: { token } });
      if (data) {
        router.push("/inbox");
        router.refresh();
      } else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  if (invalid) return <p role="alert">{t("invalid")}</p>;
  if (!preview) return <p role="status">{t("loading")}</p>;

  const button =
    "inline-block rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60";
  const sameAccount = signedInEmail?.toLowerCase() === preview.email.toLowerCase();
  return (
    <div className="space-y-4" data-testid="invitation">
      <p>{t("intro", { workspace: preview.workspaceName, role: t(`roles.${preview.role}`) })}</p>
      <p className="text-slate-700">{t("forEmail", { email: preview.email })}</p>
      {preview.role === "TECHNICIAN" && <p className="text-slate-700">{t("technicianNote")}</p>}
      <ErrorAlert message={error} />
      {preview.state !== "OPEN" ? (
        <p className="rounded border border-amber-700 bg-amber-50 px-3 py-2">{t(`state.${preview.state}`)}</p>
      ) : signedInEmail && sameAccount ? (
        <button type="button" onClick={accept} disabled={busy} aria-busy={busy} className={button}>
          {t("accept")}
        </button>
      ) : signedInEmail ? (
        <p className="rounded border border-amber-700 bg-amber-50 px-3 py-2">
          {t("otherAccount", { current: signedInEmail, email: preview.email })}
        </p>
      ) : preview.accountExists ? (
        <div className="space-y-3">
          <p>{t("haveAccount", { email: preview.email })}</p>
          <Link href="/login" className={button}>
            {t("signIn")}
          </Link>
        </div>
      ) : (
        <Link
          href={`/signup?joining=${encodeURIComponent(token)}&email=${encodeURIComponent(preview.email)}`}
          className={button}
        >
          {t("signUp")}
        </Link>
      )}
    </div>
  );
}
