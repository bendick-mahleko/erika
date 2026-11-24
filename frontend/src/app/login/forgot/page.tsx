import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { ForgotForm } from "./ForgotForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Password");
  return { title: `${t("forgotTitle")} – ERIKA` };
}

export default async function ForgotPage() {
  const t = await getTranslations("Password");
  return (
    <AuthCard title={t("forgotTitle")}>
      <ForgotForm />
    </AuthCard>
  );
}
