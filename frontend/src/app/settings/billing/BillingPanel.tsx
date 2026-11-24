"use client";

import { useState } from "react";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, idempotencyKey, problemCode, type BillingPlan, type BillingStatus } from "@/lib/api/browser";
import { useErrorText } from "@/lib/useErrorText";
import { ErrorAlert } from "@/components/forms";

/** Subscription state, plan choice (Stripe Checkout) and the Stripe customer portal (EX-03). */
export function BillingPanel({ status, isOwner }: { status: BillingStatus; isOwner: boolean }) {
  const t = useTranslations("Billing");
  const te = useErrorText();
  const format = useFormatter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const date = (iso?: string) => (iso ? format.dateTime(new Date(iso), { dateStyle: "long" }) : "");

  async function go(call: () => Promise<{ data?: { url: string }; error?: unknown }>) {
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem } = await call();
      if (data) window.location.assign(data.url);
      else setError(te(problemCode(problem)));
    } catch {
      setError(te("generic"));
    } finally {
      setBusy(false);
    }
  }

  const checkout = (plan: BillingPlan) =>
    go(() =>
      browserApi.POST("/billing/checkout", {
        params: { header: { "Idempotency-Key": idempotencyKey() } },
        body: { plan },
      }),
    );
  const portal = () =>
    go(() => browserApi.POST("/billing/portal", { params: { header: { "Idempotency-Key": idempotencyKey() } } }));

  if (!status.enabled) return <p data-testid="billing-state">{t("disabled")}</p>;
  if (status.state === "EXEMPT") return <p data-testid="billing-state">{t("exempt")}</p>;

  const running = ["active", "trialing", "past_due", "unpaid", "paused"].includes(status.subscription?.status ?? "");
  const stateStyle =
    status.state === "READ_ONLY" || status.state === "PAST_DUE"
      ? "border-amber-700 bg-amber-50 text-amber-900"
      : "border-slate-300 bg-slate-50";
  const button = "rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60";

  return (
    <div className="space-y-6">
      <ErrorAlert message={error} />
      <div className={`rounded border px-4 py-3 ${stateStyle}`} data-testid="billing-state">
        <p className="font-medium">{t(`state.${status.state}`, { date: date(status.trialEndsAt) })}</p>
        {status.subscription?.currentPeriodEnd && running && (
          <p className="text-sm">
            {status.subscription.cancelAtPeriodEnd
              ? t("endsAt", { date: date(status.subscription.currentPeriodEnd) })
              : t("renews", { date: date(status.subscription.currentPeriodEnd) })}
          </p>
        )}
      </div>

      {isOwner && !running && (
        <section aria-labelledby="plans-title" className="space-y-3">
          <h2 id="plans-title" className="text-xl font-semibold">
            {t("plans")}
          </h2>
          {status.state === "TRIAL" && <p className="text-slate-700">{t("trialKept")}</p>}
          <ul className="grid gap-4 sm:grid-cols-2">
            {status.plans
              .filter((p) => p.available)
              .map((p) => (
                <li key={p.plan} className="space-y-3 rounded border border-slate-200 p-4">
                  <p className="text-lg font-semibold">{t(`plan.${p.plan}`)}</p>
                  <p className="text-slate-700">
                    {p.netAmount && p.currency
                      ? t(`price.${p.plan}`, {
                          amount: format.number(Number(p.netAmount), { style: "currency", currency: p.currency }),
                        })
                      : t("priceUnknown")}
                  </p>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => checkout(p.plan)}
                    aria-label={t("chooseLabel", { plan: t(`plan.${p.plan}`) })}
                    className={button}
                  >
                    {t("choose", { plan: t(`plan.${p.plan}`) })}
                  </button>
                </li>
              ))}
          </ul>
        </section>
      )}

      {isOwner && status.hasCustomer && (
        <button
          type="button"
          disabled={busy}
          onClick={portal}
          className="rounded border border-brand-700 px-4 py-2 font-medium text-brand-700 hover:bg-slate-50 disabled:opacity-60"
        >
          {t("portal")}
        </button>
      )}
    </div>
  );
}
