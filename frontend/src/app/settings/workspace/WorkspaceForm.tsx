"use client";

import { useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import { browserApi, problemCode, type Workspace } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

export function WorkspaceForm({ initial, etag, canEdit }: { initial: Workspace; etag: string; canEdit: boolean }) {
  const t = useTranslations("Workspace");
  const te = useErrorText();
  const [workspace, setWorkspace] = useState(initial);
  const [version, setVersion] = useState(etag);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => {
      const value = String(form.get(name) ?? "").trim();
      return value === "" ? undefined : value;
    };
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const {
        data,
        error: problem,
        response,
      } = await browserApi.PUT("/workspace", {
        params: { header: { "If-Match": version } },
        body: {
          name: text("name") ?? "",
          street: text("street"),
          postalCode: text("postalCode"),
          city: text("city"),
          countryCode: (text("countryCode") ?? "DE").toUpperCase(),
          vatId: text("vatId")?.replace(/\s/g, ""),
          taxNumber: text("taxNumber"),
        },
      });
      if (data) {
        setWorkspace(data);
        setVersion(response.headers.get("ETag") ?? version);
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
    <form onSubmit={submit} className="max-w-xl space-y-4" aria-describedby={canEdit ? undefined : "read-only-note"}>
      {!canEdit && (
        <p id="read-only-note" className="rounded border border-slate-300 bg-slate-50 px-4 py-3">
          {t("readOnly")}
        </p>
      )}
      <ErrorAlert message={error} />
      <fieldset disabled={!canEdit || busy} className="space-y-4">
        <Field
          id="name"
          label={t("name")}
          defaultValue={workspace.name}
          required
          maxLength={200}
          autoComplete="organization"
        />
        <Field
          id="street"
          label={t("street")}
          defaultValue={workspace.street}
          maxLength={200}
          autoComplete="address-line1"
        />
        <div className="grid gap-4 sm:grid-cols-3">
          <Field
            id="postalCode"
            label={t("postalCode")}
            defaultValue={workspace.postalCode}
            maxLength={20}
            autoComplete="postal-code"
          />
          <div className="sm:col-span-2">
            <Field
              id="city"
              label={t("city")}
              defaultValue={workspace.city}
              maxLength={100}
              autoComplete="address-level2"
            />
          </div>
        </div>
        <Field
          id="countryCode"
          label={t("countryCode")}
          defaultValue={workspace.countryCode}
          required
          pattern="[A-Za-z]{2}"
          maxLength={2}
          autoComplete="country"
        />
        <Field id="vatId" label={t("vatId")} hint={t("vatIdHint")} defaultValue={workspace.vatId} maxLength={14} />
        <Field
          id="taxNumber"
          label={t("taxNumber")}
          hint={t("taxNumberHint")}
          defaultValue={workspace.taxNumber}
          maxLength={20}
        />
      </fieldset>
      {canEdit && <SubmitButton busy={busy}>{t("save")}</SubmitButton>}
      <p role="status" className="text-green-800">
        {saved ? t("saved") : ""}
      </p>
    </form>
  );
}
