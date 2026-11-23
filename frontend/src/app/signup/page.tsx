import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import Link from "next/link";
import { AuthCard } from "@/components/forms";
import { fetchSignupPolicy } from "@/lib/api/client";
import { SignupForm } from "./SignupForm";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Auth");
  return { title: `${t("signup.title")} – ERIKA` };
}

/**
 * The email address an invitation is for (ADR 0030), only to prefill the form. The link's
 * payload is readable by design; the back end checks the signature.
 */
function invitedEmail(invite: string | undefined): string | undefined {
  const payload = invite?.split(".")[1];
  if (!payload) return undefined;
  try {
    const decoded = Buffer.from(payload, "base64url").toString("utf-8");
    const email = decoded.slice(0, decoded.lastIndexOf("|"));
    return email.includes("@") ? email : undefined;
  } catch {
    return undefined;
  }
}

type Params = { invite?: string | string[]; joining?: string | string[]; email?: string | string[] };

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function SignupPage({ searchParams }: { searchParams: Promise<Params> }) {
  const t = await getTranslations("Auth");
  const params = await searchParams;
  const invite = first(params.invite);
  // A workspace invitation (ADR 0032) also counts as the closed-beta invitation.
  const joining = first(params.joining);
  const policy = await fetchSignupPolicy();
  const contact = process.env.ERIKA_BETA_CONTACT ?? "";

  if (policy.inviteOnly && !invite && !joining) {
    return (
      <AuthCard title={t("signup.title")}>
        <div className="space-y-3" data-testid="invite-only">
          <h2 className="text-lg font-semibold">{t("signup.inviteOnlyTitle")}</h2>
          <p>{t("signup.inviteOnly")}</p>
          {contact && <p>{t("signup.inviteOnlyContact", { email: contact })}</p>}
          <p className="text-sm">
            {t("signup.haveAccount")}{" "}
            <Link href="/login" className="text-brand-700 underline">
              {t("login.title")}
            </Link>
          </p>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t("signup.title")}>
      <SignupForm
        invite={invite}
        joining={joining}
        invitedEmail={joining ? first(params.email) : invitedEmail(invite)}
      />
    </AuthCard>
  );
}
