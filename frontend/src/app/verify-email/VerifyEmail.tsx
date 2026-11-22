"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

type State = { kind: "working" } | { kind: "done" } | { kind: "error"; code: string };

export function VerifyEmail() {
  const t = useTranslations("Auth");
  const te = useErrorText();
  const token = useSearchParams().get("token");
  const [state, setState] = useState<State>(token ? { kind: "working" } : { kind: "error", code: "missing" });
  const started = useRef(false);

  useEffect(() => {
    // Tokens work once: guard against React strict mode running the effect twice.
    if (!token || started.current) return;
    started.current = true;
    browserApi
      .POST("/auth/verify-email", { body: { token } })
      .then(({ error, response }) =>
        setState(response.status === 204 ? { kind: "done" } : { kind: "error", code: problemCode(error) }),
      )
      .catch(() => setState({ kind: "error", code: "generic" }));
  }, [token]);

  if (state.kind === "working") return <p role="status">{t("verify.working")}</p>;
  if (state.kind === "error") {
    return <ErrorAlert message={state.code === "missing" ? t("verify.missingToken") : te(state.code)} />;
  }
  return (
    <div role="status" className="space-y-4">
      <p>{t("verify.done")}</p>
      <Link
        href="/login"
        className="inline-block rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
      >
        {t("login.title")}
      </Link>
    </div>
  );
}
