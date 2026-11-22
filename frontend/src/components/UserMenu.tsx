import Link from "next/link";
import { getTranslations } from "next-intl/server";
import { fetchSession } from "@/lib/api/client";
import { LogoutButton } from "./LogoutButton";

/** Header navigation: sign-in links or the signed-in user with settings and sign-out. */
export async function UserMenu() {
  const t = await getTranslations("Nav");
  const session = await fetchSession();
  const link = "rounded px-2 py-1 text-white underline-offset-4 hover:underline";

  return (
    <nav aria-label={t("label")} className="ml-auto flex flex-wrap items-center gap-3 text-sm">
      {session.state === "AUTHENTICATED" && session.user ? (
        <>
          <span className="text-slate-200">{t("signedInAs", { name: session.user.displayName })}</span>
          <Link href="/settings/workspace" className={link}>
            {t("settings")}
          </Link>
          <LogoutButton label={t("signOut")} />
        </>
      ) : (
        <>
          <Link href="/login" className={link}>
            {t("signIn")}
          </Link>
          <Link href="/signup" className={link}>
            {t("signUp")}
          </Link>
        </>
      )}
    </nav>
  );
}
