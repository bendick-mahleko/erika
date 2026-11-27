"use client";

import { useState } from "react";
import Link from "next/link";
import { useFormatter, useTranslations } from "next-intl";
import {
  browserApi,
  idempotencyKey,
  problemCode,
  type BillingPlan,
  type BillingStatus,
  type MaraAddon,
} from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/**
 * MARA Stage 10 (M-AC-02, M-BR-09): the add-on's state, the free trial (after the works-council
 * notice), booking on the subscription or with a plan, cancelling, and the seats.
 */
export function MaraAddonSection({
  initial,
  billing,
  isOwner,
}: {
  initial: MaraAddon;
  billing: BillingStatus;
  isOwner: boolean;
}) {
  const t = useTranslations("MaraAddon");
  const te = useErrorText();
  const format = useFormatter();
  const [addon, setAddon] = useState(initial);
  const [notice, setNotice] = useState(initial.worksCouncilNoticeAccepted);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  const running = ["active", "trialing", "past_due"].includes(billing.subscription?.status ?? "");
  const money = (amount?: string) =>
    amount && addon.price?.currency
      ? format.number(Number(amount), { style: "currency", currency: addon.price.currency })
      : null;
  const date = (iso?: string) => (iso ? format.dateTime(new Date(iso), { dateStyle: "long" }) : "");

  async function act(call: () => Promise<{ data?: MaraAddon; error?: unknown }>, done: string) {
    setBusy(true);
    setError(null);
    setStatus("");
    try {
      const { data, error: problem } = await call();
      if (data) {
        setAddon(data);
        setStatus(t(done));
      } else {
        setError(te(problemCode(problem)));
      }
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const startTrial = () =>
    act(
      () => browserApi.POST("/billing/addons/mara/trial", { body: { worksCouncilNoticeAccepted: notice } }),
      "trialStarted",
    );
  const book = () =>
    act(
      () =>
        browserApi.POST("/billing/addons/mara/subscription", {
          params: { header: { "Idempotency-Key": idempotencyKey() } },
          body: { worksCouncilNoticeAccepted: notice },
        }),
      "bookingRequested",
    );
  const cancel = () => {
    if (!window.confirm(t("cancelConfirm"))) return;
    void act(() => browserApi.DELETE("/billing/addons/mara/subscription"), "cancelRequested");
  };

  async function checkout(plan: BillingPlan) {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await browserApi.POST("/billing/checkout", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: { plan, addons: ["MARA"], worksCouncilNoticeAccepted: notice },
      });
      if (data) window.location.assign(data.url);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const button = "rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60";
  const warn = addon.state === "READ_ONLY" || addon.state === "NONE";
  const needsNotice = !addon.worksCouncilNoticeAccepted;

  return (
    <section
      aria-labelledby="mara-addon-title"
      className="space-y-4 border-t border-slate-200 pt-6"
      data-testid="mara-addon"
    >
      <h2 id="mara-addon-title" className="text-xl font-semibold">
        {t("title")}
      </h2>
      <ErrorAlert message={error} />
      <p role="status" className="text-green-800">
        {status}
      </p>
      <div
        className={`rounded border px-4 py-3 ${warn ? "border-amber-700 bg-amber-50 text-amber-900" : "border-slate-300 bg-slate-50"}`}
        data-testid="mara-addon-state"
      >
        <p className="font-medium">{t(`state.${addon.state}`, { date: date(addon.trialEndsAt) })}</p>
        {addon.pending && <p className="text-sm">{t(`pending.${addon.pending}`)}</p>}
      </div>

      {addon.offered && (
        <>
          <p className="text-slate-700">
            {money(addon.price?.netAmount)
              ? t("price", {
                  amount: money(addon.price?.netAmount) ?? "",
                  seat: money(addon.price?.seatNetAmount) ?? "–",
                  included: addon.seats.included,
                  interval: t(`interval.${addon.price?.plan ?? "MONTHLY"}`),
                })
              : t("priceUnknown")}
          </p>
          <p className="text-slate-700" data-testid="mara-seats">
            {t("seats", { counted: addon.seats.counted, included: addon.seats.included, extra: addon.seats.extra })}
          </p>
        </>
      )}

      {isOwner && addon.offered && !["ACTIVE", "EXEMPT", "DISABLED"].includes(addon.state) && (
        <div className="space-y-3">
          {needsNotice && (
            <div className="space-y-2 rounded border border-slate-200 p-3">
              <p className="text-slate-700">{t("noticeIntro")}</p>
              <p className="flex flex-wrap gap-4 text-sm">
                <Link href="/mara/betriebsrat" className="text-brand-700 underline" target="_blank">
                  {t("noticeLink")}
                </Link>
                <Link href="/mara/mitarbeiterinformation" className="text-brand-700 underline" target="_blank">
                  {t("sheetLink")}
                </Link>
              </p>
              <label className="flex items-start gap-2">
                <input
                  type="checkbox"
                  checked={notice}
                  onChange={(e) => setNotice(e.target.checked)}
                  className="mt-1"
                  data-testid="mara-notice"
                />
                <span>{t("noticeConfirm")}</span>
              </label>
            </div>
          )}
          <div className="flex flex-wrap gap-3">
            {!addon.trialUsed && (
              <button
                type="button"
                className={button}
                disabled={busy || !notice}
                onClick={startTrial}
                data-testid="mara-trial"
              >
                {t("startTrial")}
              </button>
            )}
            {running && !addon.booked && (
              <button
                type="button"
                className={button}
                disabled={busy || !notice}
                onClick={book}
                data-testid="mara-book"
              >
                {t("book")}
              </button>
            )}
            {!running &&
              billing.plans
                .filter((p) => p.available)
                .map((p) => (
                  <button
                    key={p.plan}
                    type="button"
                    className={button}
                    disabled={busy || !notice}
                    onClick={() => checkout(p.plan)}
                  >
                    {t("bookWithPlan", { plan: t(`interval.${p.plan}`) })}
                  </button>
                ))}
          </div>
        </div>
      )}

      {isOwner && addon.state === "ACTIVE" && addon.booked && !addon.pending && (
        <button
          type="button"
          disabled={busy}
          onClick={cancel}
          className="rounded border border-red-700 px-4 py-2 font-medium text-red-700 disabled:opacity-60"
        >
          {t("cancel")}
        </button>
      )}
      <p className="text-sm">
        <Link href="/mara/mitarbeiterinformation" className="text-brand-700 underline">
          {t("sheetLink")}
        </Link>
      </p>
    </section>
  );
}
