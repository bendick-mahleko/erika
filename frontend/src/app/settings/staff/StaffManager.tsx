"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type StaffLevel, type StaffMember } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/** Hourly rates per staff level and each person's level and price visibility (M-OF-04, ADR 0039). */
export function StaffManager({
  initialLevels,
  initialStaff,
  isOwner,
}: {
  initialLevels: StaffLevel[];
  initialStaff: StaffMember[];
  isOwner: boolean;
}) {
  const t = useTranslations("Staff");
  const tr = useTranslations("Team");
  const te = useErrorText();
  const [levels, setLevels] = useState(initialLevels);
  const [staff, setStaff] = useState(initialStaff);
  const [editing, setEditing] = useState<StaffLevel | "new" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  async function saveLevel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = {
      name: String(form.get("levelName") ?? "").trim(),
      hourlyRate: String(form.get("hourlyRate") ?? "")
        .trim()
        .replace(",", "."),
      archived: form.get("archived") === "on",
    };
    setBusy(true);
    setError(null);
    setMessage("");
    const result =
      editing === "new"
        ? await browserApi.POST("/staff-levels", { params: { header: { "Idempotency-Key": idempotencyKey() } }, body })
        : await browserApi.PUT("/staff-levels/{staffLevelId}", {
            params: {
              path: { staffLevelId: (editing as StaffLevel).id },
              header: { "If-Match": `"${(editing as StaffLevel).version}"` },
            },
            body,
          });
    setBusy(false);
    if (result.data) {
      const saved = result.data;
      setLevels((list) =>
        [...list.filter((l) => l.id !== saved.id), saved].sort((a, b) => a.name.localeCompare(b.name, "de")),
      );
      setEditing(null);
      setMessage(t("saved"));
    } else {
      setError(te(problemCode(result.error)));
    }
  }

  async function saveStaff(member: StaffMember, change: Partial<Pick<StaffMember, "staffLevelId" | "canSeePrices">>) {
    const next = { ...member, ...change };
    setStaff((list) => list.map((s) => (s.memberId === member.memberId ? next : s)));
    setError(null);
    setMessage("");
    const { data, error: problem } = await browserApi.PUT("/staff/{memberId}", {
      params: { path: { memberId: member.memberId } },
      body: { staffLevelId: next.staffLevelId || undefined, canSeePrices: next.canSeePrices },
    });
    if (data) {
      setStaff((list) => list.map((s) => (s.memberId === data.memberId ? data : s)));
      setMessage(t("saved"));
    } else {
      setStaff((list) => list.map((s) => (s.memberId === member.memberId ? member : s)));
      setError(te(problemCode(problem)));
    }
  }

  const current = editing && editing !== "new" ? editing : undefined;
  const activeLevels = levels.filter((l) => !l.archived);

  return (
    <div className="space-y-8">
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {message}
      </p>
      {!isOwner && <p className="text-slate-700">{t("ownerOnly")}</p>}

      <section aria-labelledby="levels-title" className="space-y-4">
        <h2 id="levels-title" className="text-xl font-semibold">
          {t("levelsTitle")}
        </h2>
        {levels.length === 0 ? (
          <p className="text-slate-700">{t("noLevels")}</p>
        ) : (
          <ul className="divide-y divide-slate-200" data-testid="staff-levels">
            {levels.map((l) => (
              <li key={l.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <span>
                  <span className="font-medium">{l.name}</span>
                  <span className="ml-2 text-slate-700">{l.hourlyRate} €</span>
                  {l.archived && <span className="ml-2 text-sm text-slate-500">({t("archived")})</span>}
                </span>
                {isOwner && editing === null && (
                  <button type="button" className="text-brand-700 underline" onClick={() => setEditing(l)}>
                    {t("edit")}
                    <span className="sr-only">: {l.name}</span>
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
        {isOwner && editing === null && (
          <button
            type="button"
            className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900"
            onClick={() => setEditing("new")}
          >
            {t("newLevel")}
          </button>
        )}
        {editing !== null && (
          <form
            method="post"
            onSubmit={saveLevel}
            className="max-w-xl space-y-4 rounded border border-slate-300 p-4"
            key={current?.id ?? "new"}
          >
            <fieldset disabled={busy} className="grid gap-4 sm:grid-cols-2">
              <legend className="mb-2 font-semibold">{current ? current.name : t("newLevel")}</legend>
              <Field id="levelName" label={t("levelName")} required maxLength={100} defaultValue={current?.name} />
              <Field
                id="hourlyRate"
                label={t("hourlyRate")}
                required
                inputMode="decimal"
                pattern="[0-9]{1,12}([.,][0-9]{1,4})?"
                defaultValue={current?.hourlyRate}
              />
              {current && (
                <div className="flex items-center gap-2 sm:col-span-2">
                  <input id="archived" name="archived" type="checkbox" defaultChecked={current.archived} />
                  <label htmlFor="archived">{t("archiveLevel")}</label>
                </div>
              )}
            </fieldset>
            <div className="flex gap-4">
              <SubmitButton busy={busy}>{t("save")}</SubmitButton>
              <button type="button" className="underline" onClick={() => setEditing(null)}>
                {t("cancel")}
              </button>
            </div>
          </form>
        )}
      </section>

      <section aria-labelledby="staff-title" className="space-y-4">
        <h2 id="staff-title" className="text-xl font-semibold">
          {t("staffTitle")}
        </h2>
        {staff.length === 0 ? (
          <p className="text-slate-700">{t("noStaff")}</p>
        ) : (
          <ul className="divide-y divide-slate-200" data-testid="staff-list">
            {staff.map((s) => (
              <li key={s.memberId} className="flex flex-wrap items-center gap-4 py-3">
                <span className="min-w-48">
                  <span className="font-medium">{s.displayName}</span>
                  <span className="ml-2 text-sm text-slate-600">{tr(`roles.${s.role}`)}</span>
                </span>
                <span className="flex items-center gap-2">
                  <label htmlFor={`level-${s.memberId}`} className="sr-only">
                    {t("staffLevelOf", { name: s.displayName })}
                  </label>
                  <select
                    id={`level-${s.memberId}`}
                    className="rounded border border-slate-400 px-2 py-1"
                    value={s.staffLevelId ?? ""}
                    disabled={!isOwner}
                    onChange={(e) => saveStaff(s, { staffLevelId: e.target.value || undefined })}
                  >
                    <option value="">{t("noLevel")}</option>
                    {activeLevels.map((l) => (
                      <option key={l.id} value={l.id}>
                        {l.name} ({l.hourlyRate} €)
                      </option>
                    ))}
                  </select>
                </span>
                <span className="flex items-center gap-2">
                  <input
                    id={`prices-${s.memberId}`}
                    type="checkbox"
                    className="h-5 w-5"
                    checked={s.canSeePrices}
                    disabled={!isOwner}
                    onChange={(e) => saveStaff(s, { canSeePrices: e.target.checked })}
                  />
                  <label htmlFor={`prices-${s.memberId}`}>
                    {t("canSeePrices")}
                    <span className="sr-only">: {s.displayName}</span>
                  </label>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
