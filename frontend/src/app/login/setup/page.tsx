import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { fetchSession } from "@/lib/api/client";
import { TotpSetup } from "./TotpSetup";

export default async function TotpSetupPage() {
  const session = await fetchSession();
  if (session.state === "TOTP_REQUIRED") redirect("/login/totp");
  if (session.state !== "TOTP_ENROLMENT_REQUIRED") redirect("/login");
  const t = await getTranslations("Auth");
  return (
    <AuthCard title={t("setup.title")}>
      <TotpSetup />
    </AuthCard>
  );
}
