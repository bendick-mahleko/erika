import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { SignupForm } from "./SignupForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Auth");
  return { title: `${t("signup.title")} – ERIKA` };
}

export default async function SignupPage() {
  const t = await getTranslations("Auth");
  return (
    <AuthCard title={t("signup.title")}>
      <SignupForm />
    </AuthCard>
  );
}
