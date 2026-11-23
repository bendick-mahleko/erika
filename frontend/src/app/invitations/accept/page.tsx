import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AuthCard } from "@/components/forms";
import { fetchSession } from "@/lib/api/client";
import { AcceptInvitation } from "./AcceptInvitation";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Invitation");
  return { title: `${t("title")} – ERIKA` };
}

export default async function AcceptInvitationPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string | string[] }>;
}) {
  const t = await getTranslations("Invitation");
  const raw = (await searchParams).token;
  const token = Array.isArray(raw) ? raw[0] : raw;
  const session = await fetchSession();
  const signedInEmail = session.state === "AUTHENTICATED" ? session.user?.email : undefined;
  return (
    <AuthCard title={t("title")}>
      {token ? <AcceptInvitation token={token} signedInEmail={signedInEmail} /> : <p role="alert">{t("invalid")}</p>}
    </AuthCard>
  );
}
