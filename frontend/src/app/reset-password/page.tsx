import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { ResetForm } from "./ResetForm";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Password");
  return { title: `${t("resetTitle")} – ERIKA` };
}

export default async function ResetPasswordPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string | string[] }>;
}) {
  const t = await getTranslations("Password");
  const raw = (await searchParams).token;
  const token = Array.isArray(raw) ? raw[0] : raw;
  return (
    <AuthCard title={t("resetTitle")}>
      {token ? <ResetForm token={token} /> : <p role="alert">{t("noToken")}</p>}
    </AuthCard>
  );
}
