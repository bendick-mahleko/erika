"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { browserApi, problemCode, type InvoiceSettings } from "@/lib/api/browser";
import { FORMATS } from "@/lib/creator";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert, Field, SubmitButton } from "@/components/forms";

/** Seller contact, bank account, Kleinunternehmer flag, number patterns and defaults (owner only). */
export function InvoiceSettingsForm({
  initial,
  etag,
  canEdit,
}: {
  initial: InvoiceSettings;
  etag: string;
  canEdit: boolean;
}) {
  const t = useTranslations("InvoiceSettings");
  const tc = useTranslations("Creator");
  const tf = useTranslations("Editor");
  const te = useErrorText();
  const [settings, setSettings] = useState(initial);
  const [version, setVersion] = useState(etag);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim() || undefined;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const {
        data,
        error: problem,
        response,
      } = await browserApi.PUT("/invoice-settings", {
        params: { header: { "If-Match": version } },
        body: {
          contactName: text("contactName"),
          contactPhone: text("contactPhone"),
          contactEmail: text("contactEmail"),
          legalRegistration: text("legalRegistration"),
          iban: text("iban")?.replace(/\s/g, ""),
          bic: text("bic")?.replace(/\s/g, ""),
          accountHolder: text("accountHolder"),
          kleinunternehmer: form.get("kleinunternehmer") === "on",
          numberPattern: text("numberPattern") ?? "",
          creditNotePattern: text("creditNotePattern") ?? "",
          defaultFormat: (text("defaultFormat") ?? "ZUGFERD_EN16931") as InvoiceSettings["defaultFormat"],
          paymentTermsDays: Number(text("paymentTermsDays") ?? "14"),
        },
      });
      if (data) {
        setSettings(data);
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

  const s = settings.seller;
  const missing = settings.missing.map((m) =>
    tf.has(`field.${m}`) ? tf(`field.${m}`) : tf.has(`field.settings.${m}`) ? tf(`field.settings.${m}`) : m,
  );

  return (
    <div className="space-y-6">
      <section aria-labelledby="seller-heading" className="space-y-2 rounded border border-slate-200 p-4">
        <h2 id="seller-heading" className="font-semibold">
          {t("sellerHeading")}
        </h2>
        <p>
          {s.name}
          <br />
          {[s.street, [s.postalCode, s.city].filter(Boolean).join(" "), s.countryCode].filter(Boolean).join(", ")}
          <br />
          {[s.vatId && `USt-IdNr. ${s.vatId}`, s.taxNumber && `Steuernummer ${s.taxNumber}`]
            .filter(Boolean)
            .join(" · ")}
        </p>
        <Link href="/settings/workspace" className="text-brand-700 underline">
          {t("editSeller")}
        </Link>
        <p className={missing.length ? "text-amber-900" : "text-green-800"} data-testid="settings-missing">
          {missing.length ? t("missing", { fields: missing.join(", ") }) : t("complete")}
        </p>
      </section>

      <form
        onSubmit={submit}
        className="max-w-2xl space-y-6"
        aria-describedby={canEdit ? undefined : "settings-read-only"}
      >
        {!canEdit && (
          <p id="settings-read-only" className="rounded border border-slate-300 bg-slate-50 px-4 py-3">
            {t("readOnly")}
          </p>
        )}
        <ErrorAlert message={error} />
        <fieldset disabled={!canEdit || busy} className="space-y-6">
          <fieldset className="grid gap-4 sm:grid-cols-2">
            <legend className="mb-2 font-semibold">{t("contactHeading")}</legend>
            <Field
              id="contactName"
              label={t("contactName")}
              maxLength={200}
              defaultValue={settings.contactName}
              autoComplete="name"
            />
            <Field
              id="contactPhone"
              label={t("contactPhone")}
              maxLength={50}
              defaultValue={settings.contactPhone}
              autoComplete="tel"
            />
            <div className="sm:col-span-2">
              <Field
                id="contactEmail"
                label={t("contactEmail")}
                hint={t("contactEmailHint")}
                type="email"
                maxLength={320}
                defaultValue={settings.contactEmail}
                autoComplete="email"
              />
            </div>
            <div className="sm:col-span-2">
              <Field
                id="legalRegistration"
                label={t("legalRegistration")}
                hint={t("legalRegistrationHint")}
                maxLength={200}
                defaultValue={settings.legalRegistration}
              />
            </div>
          </fieldset>
          <fieldset className="grid gap-4 sm:grid-cols-2">
            <legend className="mb-2 font-semibold">{t("bankHeading")}</legend>
            <Field id="iban" label={t("iban")} maxLength={40} defaultValue={settings.iban} />
            <Field id="bic" label={t("bic")} maxLength={11} defaultValue={settings.bic} />
            <div className="sm:col-span-2">
              <Field
                id="accountHolder"
                label={t("accountHolder")}
                hint={t("accountHolderHint")}
                maxLength={200}
                defaultValue={settings.accountHolder}
              />
            </div>
          </fieldset>
          <fieldset className="space-y-2">
            <legend className="mb-2 font-semibold">{t("taxHeading")}</legend>
            <div className="flex items-start gap-2">
              <input
                id="kleinunternehmer"
                name="kleinunternehmer"
                type="checkbox"
                defaultChecked={settings.kleinunternehmer}
                aria-describedby="kleinunternehmer-hint"
                className="mt-1"
              />
              <div>
                <label htmlFor="kleinunternehmer">{t("kleinunternehmer")}</label>
                <p id="kleinunternehmer-hint" className="text-sm text-slate-600">
                  {t("kleinunternehmerHint")}
                </p>
              </div>
            </div>
          </fieldset>
          <fieldset className="grid gap-4 sm:grid-cols-2">
            <legend className="mb-2 font-semibold">{t("numbersHeading")}</legend>
            <div className="sm:col-span-2">
              <Field
                id="numberPattern"
                label={t("numberPattern")}
                hint={t("numberPatternHint")}
                required
                maxLength={40}
                defaultValue={settings.numberPattern}
              />
            </div>
            <div className="sm:col-span-2">
              <Field
                id="creditNotePattern"
                label={t("creditNotePattern")}
                hint={t("creditNotePatternHint")}
                required
                maxLength={40}
                defaultValue={settings.creditNotePattern}
              />
            </div>
            <p className="text-slate-700 sm:col-span-2" data-testid="next-number">
              {t("nextNumber", { number: settings.nextNumber })}
            </p>
            <div className="space-y-1">
              <label htmlFor="defaultFormat" className="block font-medium">
                {t("defaultFormat")}
              </label>
              <select
                id="defaultFormat"
                name="defaultFormat"
                defaultValue={settings.defaultFormat}
                className="block w-full rounded border border-slate-400 px-3 py-2"
              >
                {FORMATS.map((f) => (
                  <option key={f} value={f}>
                    {tc(`format.${f}`)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              id="paymentTermsDays"
              label={t("paymentTermsDays")}
              type="number"
              min={0}
              max={365}
              required
              defaultValue={settings.paymentTermsDays}
            />
          </fieldset>
        </fieldset>
        {canEdit && <SubmitButton busy={busy}>{t("save")}</SubmitButton>}
        <p role="status" className="text-green-800">
          {saved ? t("saved") : ""}
        </p>
      </form>
    </div>
  );
}
