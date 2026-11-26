"use client";

import { useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, problemCode, type Device } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/** Devices grouped by person, active ones first; "Abmelden" locks a phone out at once. */
export function DevicesManager({ initialDevices }: { initialDevices: Device[] }) {
  const t = useTranslations("Devices");
  const tr = useTranslations("Team");
  const te = useErrorText();
  const format = useFormatter();
  const [devices, setDevices] = useState(initialDevices);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  const when = (iso?: string) =>
    iso ? format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" }) : "–";

  async function revoke(device: Device) {
    setBusy(true);
    setError(null);
    setMessage("");
    const { data, error: problem } = await browserApi.POST("/devices/{deviceId}/revoke", {
      params: { path: { deviceId: device.id } },
    });
    setBusy(false);
    setConfirming(null);
    if (data) {
      setDevices((list) => list.map((d) => (d.id === data.id ? data : d)));
      setMessage(t("revoked", { name: data.name, person: data.displayName }));
    } else {
      setError(te(problemCode(problem)));
    }
  }

  const byUser = new Map<string, Device[]>();
  for (const d of devices) byUser.set(d.userId, [...(byUser.get(d.userId) ?? []), d]);
  const people = [...byUser.entries()].map(([userId, own]) => ({
    userId,
    displayName: own[0]?.displayName ?? "",
    role: own[0]?.role,
    devices: own,
  }));
  people.sort((a, b) => a.displayName.localeCompare(b.displayName, "de"));

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      {people.length === 0 ? (
        <p className="text-slate-700">{t("none")}</p>
      ) : (
        people.map((person) => (
          <section key={person.userId} aria-labelledby={`person-${person.userId}`} className="space-y-2">
            <h2 id={`person-${person.userId}`} className="text-lg font-semibold">
              {person.displayName}
              {person.role && (
                <span className="ml-2 text-sm font-normal text-slate-600">{tr(`roles.${person.role}`)}</span>
              )}
              {!person.role && <span className="ml-2 text-sm font-normal text-slate-600">{t("leftWorkspace")}</span>}
            </h2>
            <ul className="divide-y divide-slate-200" data-testid="device-list">
              {person.devices.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <span>
                    <span className="font-medium">{d.name}</span>
                    <span className="ml-2 text-sm text-slate-600">
                      {t(`platforms.${d.platform}`)}
                      {d.appVersion && ` · ${t("appVersion", { version: d.appVersion })}`}
                    </span>
                    <span className="block text-sm text-slate-700">
                      {t("signedInAt", { when: when(d.createdAt) })} · {t("lastSeen", { when: when(d.lastSeenAt) })}
                    </span>
                    {d.revokedAt && (
                      <span className="block text-sm text-red-800" data-testid="device-revoked">
                        {t(`reasons.${d.revokedReason ?? "OWNER"}`, { when: when(d.revokedAt) })}
                      </span>
                    )}
                  </span>
                  {!d.revokedAt &&
                    (confirming === d.id ? (
                      <span className="flex flex-wrap items-center gap-3">
                        <span>{t("confirm", { name: d.name })}</span>
                        <button
                          type="button"
                          disabled={busy}
                          className="rounded bg-red-700 px-4 py-2 font-medium text-white hover:bg-red-900 disabled:opacity-60"
                          onClick={() => revoke(d)}
                        >
                          {t("revokeNow")}
                        </button>
                        <button type="button" className="underline" onClick={() => setConfirming(null)}>
                          {t("cancel")}
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="rounded border border-red-700 px-4 py-2 font-medium text-red-800 hover:bg-red-50"
                        onClick={() => setConfirming(d.id)}
                      >
                        {t("revoke")}
                        <span className="sr-only">: {t("deviceOf", { name: d.name, person: d.displayName })}</span>
                      </button>
                    ))}
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </div>
  );
}
