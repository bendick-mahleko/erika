import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchSession } from "@/lib/api/client";
import { SettingsNav } from "@/components/SettingsNav";
import { ChangePasswordForm } from "./ChangePasswordForm";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Password");
  return { title: `${t("accountTitle")} – ERIKA` };
}

export const dynamic = "force-dynamic";

export default async function AccountPage() {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED" || !session.user) redirect("/login");
  const t = await getTranslations("Password");
  return (
    <div className="space-y-6">
      <SettingsNav current="account" />
      <div>
        <h1 className="text-2xl font-semibold">{t("accountTitle")}</h1>
        <p className="mt-2 text-slate-700">{t("accountIntro", { email: session.user.email })}</p>
      </div>
      <ChangePasswordForm />
    </div>
  );
}
