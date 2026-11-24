import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchSession, fetchSignupPolicy } from "@/lib/api/client";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Home");
  return { title: "ERIKA", description: t("metaDescription") };
}

const FEATURES = ["receive", "check", "archive", "create", "advisor", "security"] as const;

/** Landing page for visitors; signed-in users go straight to their inbox. */
export default async function HomePage() {
  const session = await fetchSession();
  if (session.state === "AUTHENTICATED") redirect("/inbox");
  const t = await getTranslations("Home");
  const policy = await fetchSignupPolicy();
  const contact = process.env.ERIKA_BETA_CONTACT ?? "";
  const price = process.env.ERIKA_PRICE_TEXT ?? "";
  const primary = "rounded bg-brand-700 px-5 py-3 font-medium text-white hover:bg-brand-900";
  const secondary = "rounded border border-brand-700 px-5 py-3 font-medium text-brand-700 hover:bg-slate-50";

  return (
    <div className="space-y-12">
      <section aria-labelledby="hero-title" className="space-y-5">
        <h1 id="hero-title" className="text-3xl font-semibold sm:text-4xl">
          {t("title")}
        </h1>
        <p className="max-w-3xl text-lg text-slate-700">{t("intro")}</p>
        <div className="flex flex-wrap gap-3">
          {policy.inviteOnly ? (
            contact && (
              <a href={`mailto:${contact}?subject=${encodeURIComponent(t("betaSubject"))}`} className={primary}>
                {t("ctaBeta")}
              </a>
            )
          ) : (
            <Link href="/signup" className={primary}>
              {t("ctaSignup")}
            </Link>
          )}
          <Link href="/login" className={secondary}>
            {t("ctaLogin")}
          </Link>
        </div>
        <p className="text-sm text-slate-600">{policy.inviteOnly ? t("betaNote") : price || t("trialNote")}</p>
      </section>

      <section aria-labelledby="why-title" className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-6">
        <h2 id="why-title" className="text-xl font-semibold">
          {t("whyTitle")}
        </h2>
        <ul className="list-disc space-y-1 pl-5 text-slate-800">
          <li>{t("why2025")}</li>
          <li>{t("why2027")}</li>
          <li>{t("why2028")}</li>
        </ul>
      </section>

      <section aria-labelledby="features-title" className="space-y-4">
        <h2 id="features-title" className="text-xl font-semibold">
          {t("featuresTitle")}
        </h2>
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f) => (
            <li key={f} className="space-y-2 rounded-lg border border-slate-200 p-5">
              <h3 className="font-semibold">{t(`feature.${f}.title`)}</h3>
              <p className="text-slate-700">{t(`feature.${f}.text`)}</p>
            </li>
          ))}
        </ul>
      </section>

      <p className="text-sm">
        <Link href="/systemstatus" className="text-brand-700 underline">
          {t("statusLink")}
        </Link>
      </p>
    </div>
  );
}
