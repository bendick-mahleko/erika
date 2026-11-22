"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/** "Email me when new documents arrive" (Stage 5 notifications); saved as soon as it changes. */
export function NotificationForm({ initial, canEdit }: { initial: boolean; canEdit: boolean }) {
  const t = useTranslations("Notifications");
  const te = useErrorText();
  const [checked, setChecked] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function change(value: boolean) {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const { data, error: problem } = await browserApi.PUT("/notification-settings", {
        body: { emailOnNewDocuments: value },
      });
      if (data) {
        setChecked(data.emailOnNewDocuments);
        setSaved(true);
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
    <section aria-labelledby="notifications-heading" className="max-w-xl space-y-3">
      <h2 id="notifications-heading" className="text-lg font-semibold">
        {t("heading")}
      </h2>
      <ErrorAlert message={error} />
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={checked}
          disabled={!canEdit || busy}
          onChange={(e) => change(e.target.checked)}
          className="h-4 w-4"
        />
        {t("emailOnNewDocuments")}
      </label>
      <p role="status" className="text-green-800">
        {saved ? t("saved") : ""}
      </p>
    </section>
  );
}
