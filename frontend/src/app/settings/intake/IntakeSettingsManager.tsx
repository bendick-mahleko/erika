"use client";

import { useState, type FormEvent } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type IntakeSettings, type QuarantinedMail } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/**
 * IN-06: only email from allowed senders is imported while the allow-list is on; other email
 * waits in the quarantine until someone imports or discards it. Owners configure, members decide.
 */
export function IntakeSettingsManager({
  initial,
  quarantine: initialQuarantine,
  canConfigure,
  canDecide,
}: {
  initial: IntakeSettings;
  quarantine: QuarantinedMail[];
  canConfigure: boolean;
  canDecide: boolean;
}) {
  const t = useTranslations("IntakeSettings");
  const te = useErrorText();
  const format = useFormatter();
  const [settings, setSettings] = useState(initial);
  const [quarantine, setQuarantine] = useState(initialQuarantine);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  async function run(action: () => Promise<string | null>) {
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const problem = await action();
      if (problem) setError(te(problem));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const toggle = (enabled: boolean) => {
    // Shown at once; put back if saving fails.
    setSettings((s) => ({ ...s, allowListEnabled: enabled }));
    return run(async () => {
      const { data, error: problem } = await browserApi.PUT("/intake/settings", {
        body: { allowListEnabled: enabled },
      });
      if (!data) {
        setSettings((s) => ({ ...s, allowListEnabled: !enabled }));
        return problemCode(problem);
      }
      setSettings(data);
      setStatus(t("saved"));
      return null;
    });
  };

  const addSender = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const pattern = String(new FormData(form).get("senderPattern") ?? "").trim();
    if (!pattern) return;
    return run(async () => {
      const { data, error: problem } = await browserApi.POST("/intake/senders", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: { pattern },
      });
      if (!data) return problemCode(problem);
      setSettings((s) => ({
        ...s,
        senders: [...s.senders.filter((x) => x.id !== data.id), data].sort((a, b) =>
          a.pattern.localeCompare(b.pattern),
        ),
      }));
      form.reset();
      setStatus(t("senderAdded", { pattern: data.pattern }));
      return null;
    });
  };

  const removeSender = (id: string, pattern: string) =>
    run(async () => {
      const { error: problem, response } = await browserApi.DELETE("/intake/senders/{senderId}", {
        params: { path: { senderId: id } },
      });
      if (!response.ok) return problemCode(problem);
      setSettings((s) => ({ ...s, senders: s.senders.filter((x) => x.id !== id) }));
      setStatus(t("senderRemoved", { pattern }));
      return null;
    });

  const decide = (mail: QuarantinedMail, decision: "release" | "allow" | "discard") =>
    run(async () => {
      const path = { params: { path: { intakeId: mail.id } } };
      const { error: problem, response } =
        decision === "discard"
          ? await browserApi.POST("/intake/quarantine/{intakeId}/discard", path)
          : await browserApi.POST("/intake/quarantine/{intakeId}/release", {
              ...path,
              body: { allowSender: decision === "allow" },
            });
      if (!response.ok) return problemCode(problem);
      setQuarantine((q) => q.filter((m) => m.id !== mail.id));
      setStatus(t(decision === "discard" ? "discarded" : "released"));
      if (decision === "allow") {
        const { data } = await browserApi.GET("/intake/settings");
        if (data) setSettings(data);
      }
      return null;
    });

  const button = "rounded border px-3 py-1 text-sm disabled:opacity-60";

  return (
    <div className="space-y-8">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {status}
      </p>

      <section aria-labelledby="allow-list-heading" className="max-w-2xl space-y-4">
        <h2 id="allow-list-heading" className="text-lg font-semibold">
          {t("allowListHeading")}
        </h2>
        <div className="flex items-start gap-2">
          <input
            id="allowListEnabled"
            type="checkbox"
            checked={settings.allowListEnabled}
            disabled={!canConfigure || busy}
            onChange={(e) => toggle(e.target.checked)}
            aria-describedby="allowListEnabled-hint"
            className="mt-1 h-4 w-4"
          />
          <div>
            <label htmlFor="allowListEnabled">{t("allowListEnabled")}</label>
            <p id="allowListEnabled-hint" className="text-sm text-slate-600">
              {t("allowListHint")}
            </p>
          </div>
        </div>

        <h3 className="font-semibold">{t("sendersHeading")}</h3>
        {settings.senders.length === 0 ? (
          <p className="text-slate-700">{t("noSenders")}</p>
        ) : (
          <ul className="divide-y divide-slate-200 rounded border border-slate-200" data-testid="sender-list">
            {settings.senders.map((s) => (
              <li key={s.id} className="flex items-center justify-between gap-3 px-3 py-2">
                <span className="break-all">{s.pattern}</span>
                {canConfigure && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => removeSender(s.id, s.pattern)}
                    className={`${button} border-red-700 text-red-800`}
                    aria-label={t("removeSender", { pattern: s.pattern })}
                  >
                    {t("remove")}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
        {canConfigure && (
          <form method="post" onSubmit={addSender} className="flex flex-wrap items-end gap-3">
            <div className="min-w-[16rem] flex-1">
              <Field
                id="senderPattern"
                label={t("senderPattern")}
                hint={t("senderPatternHint")}
                maxLength={320}
                required
                disabled={busy}
              />
            </div>
            <SubmitButton busy={busy}>{t("addSender")}</SubmitButton>
          </form>
        )}
      </section>

      <section aria-labelledby="quarantine-heading" className="space-y-3">
        <h2 id="quarantine-heading" className="text-lg font-semibold">
          {t("quarantineHeading")}
        </h2>
        <p className="max-w-3xl text-slate-700">{t("quarantineIntro")}</p>
        {quarantine.length === 0 ? (
          <p className="text-slate-700" data-testid="quarantine-empty">
            {t("quarantineEmpty")}
          </p>
        ) : (
          <ul className="space-y-3" data-testid="quarantine-list">
            {quarantine.map((m) => (
              <li key={m.id} className="space-y-2 rounded border border-slate-200 p-3">
                <p className="font-medium break-all">{m.fromAddress ?? m.sender ?? t("unknownSender")}</p>
                <p className="text-sm text-slate-700">
                  {[m.subject, format.dateTime(new Date(m.receivedAt), { dateStyle: "medium", timeStyle: "short" })]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
                {canDecide && (
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => decide(m, "release")}
                      className={`${button} border-brand-700 text-brand-700`}
                    >
                      {t("release")}
                    </button>
                    {canConfigure && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => decide(m, "allow")}
                        className={`${button} border-brand-700 text-brand-700`}
                      >
                        {t("releaseAndAllow")}
                      </button>
                    )}
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => decide(m, "discard")}
                      className={`${button} border-red-700 text-red-800`}
                    >
                      {t("discard")}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
