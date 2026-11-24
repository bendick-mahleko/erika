import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { fetchInboxSummary, fetchSession } from "@/lib/api/client";
import { FeedbackButton } from "./FeedbackButton";
import { LanguageSwitcher } from "./LanguageSwitcher";
import { LogoutButton } from "./LogoutButton";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

/** Header navigation: sign-in links or the signed-in user with settings and sign-out. */
export async function UserMenu() {
  const t = await getTranslations("Nav");
  const session = await fetchSession();
  const summary = session.state === "AUTHENTICATED" ? await fetchInboxSummary() : null;
  const badge = summary?.newDocuments ?? 0;
  const link = "rounded px-2 py-1 text-white underline-offset-4 hover:underline";

  return (
    <nav aria-label={t("label")} className="ml-auto flex flex-wrap items-center gap-3 text-sm">
      {session.state === "AUTHENTICATED" && session.user ? (
        <>
          <Link href="/inbox" className={link}>
            {t("inbox")}
            {badge > 0 && (
              <span
                className="ml-1 rounded-full bg-white px-2 py-0.5 text-xs font-semibold text-brand-900"
                aria-label={t("inboxBadge", { count: badge })}
                data-testid="inbox-badge"
              >
                {badge}
              </span>
            )}
          </Link>
          <Link href="/invoices" className={link}>
            {t("invoices")}
          </Link>
          <Link href="/exports" className={link}>
            {t("exports")}
          </Link>
          {session.workspace && (session.workspaces?.length ?? 0) > 1 && (
            <WorkspaceSwitcher current={session.workspace.id} workspaces={session.workspaces ?? []} />
          )}
          <span className="text-slate-200">{t("signedInAs", { name: session.user.displayName })}</span>
          <Link href="/settings/workspace" className={link}>
            {t("settings")}
          </Link>
          <FeedbackButton />
          <LogoutButton label={t("signOut")} />
          <LanguageSwitcher signedIn />
        </>
      ) : (
        <>
          <Link href="/login" className={link}>
            {t("signIn")}
          </Link>
          <Link href="/signup" className={link}>
            {t("signUp")}
          </Link>
          <LanguageSwitcher signedIn={false} />
        </>
      )}
    </nav>
  );
}
