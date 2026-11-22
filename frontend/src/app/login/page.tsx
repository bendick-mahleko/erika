import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { fetchSession } from "@/lib/api/client";
import { LoginForm } from "./LoginForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Auth");
  return { title: `${t("login.title")} – ERIKA` };
}

export default async function LoginPage() {
  if ((await fetchSession()).state === "AUTHENTICATED") redirect("/settings/workspace");
  const t = await getTranslations("Auth");
  return (
    <AuthCard title={t("login.title")}>
      <LoginForm />
    </AuthCard>
  );
}
