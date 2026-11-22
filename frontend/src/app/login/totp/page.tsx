import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { fetchSession } from "@/lib/api/client";
import { TotpForm } from "./TotpForm";

export default async function TotpPage() {
  const session = await fetchSession();
  if (session.state === "AUTHENTICATED") redirect("/settings/workspace");
  if (session.state === "TOTP_ENROLMENT_REQUIRED") redirect("/login/setup");
  if (session.state !== "TOTP_REQUIRED") redirect("/login");
  const t = await getTranslations("Auth");
  return (
    <AuthCard title={t("totp.title")}>
      <TotpForm />
    </AuthCard>
  );
}
