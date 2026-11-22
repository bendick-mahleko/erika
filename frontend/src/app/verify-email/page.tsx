import { Suspense } from "react";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { VerifyEmail } from "./VerifyEmail";

export default async function VerifyEmailPage() {
  const t = await getTranslations("Auth");
  return (
    <AuthCard title={t("verify.title")}>
      <Suspense fallback={<p role="status">{t("verify.working")}</p>}>
        <VerifyEmail />
      </Suspense>
    </AuthCard>
  );
}
