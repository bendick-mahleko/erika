"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

type Settings = { emailOnNewDocuments: boolean; emailOnDueDates: boolean };

/** Email notifications: new documents (Stage 5) and due dates (AR-05); saved as soon as they change. */
export function NotificationForm({ initial, canEdit }: { initial: Settings; canEdit: boolean }) {
  const t = useTranslations("Notifications");
  const te = useErrorText();
  const [settings, setSettings] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function change(next: Settings) {
    const before = settings;
    setSettings(next); // shown at once; put back if saving fails
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const { data, error: problem } = await browserApi.PUT("/notification-settings", { body: next });
      if (data) {
        setSettings({ emailOnNewDocuments: data.emailOnNewDocuments, emailOnDueDates: data.emailOnDueDates ?? true });
        setSaved(true);
      } else {
        setSettings(before);
        setError(te(problemCode(problem)));
      }
    } catch {
      setSettings(before);
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
      {(["emailOnNewDocuments", "emailOnDueDates"] as const).map((key) => (
        <label key={key} className="flex items-center gap-2">
          <input
            type="checkbox"
            name={key}
            checked={settings[key]}
            disabled={!canEdit || busy}
            onChange={(e) => change({ ...settings, [key]: e.target.checked })}
            className="h-4 w-4"
          />
          {t(key)}
        </label>
      ))}
      <p role="status" className="text-green-800">
        {saved ? t("saved") : ""}
      </p>
    </section>
  );
}
