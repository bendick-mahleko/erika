"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { browserApi } from "@/lib/api/browser";

/** For users in several workspaces (e.g. a tax advisor with several clients). */
export function WorkspaceSwitcher({
  current,
  workspaces,
}: {
  current: string;
  workspaces: { id: string; name: string; role: string }[];
}) {
  const t = useTranslations("Nav");
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  return (
    <label className="flex items-center gap-2 text-white">
      <span className="sr-only">{t("workspace")}</span>
      <select
        value={current}
        disabled={busy}
        data-testid="workspace-switcher"
        className="rounded border border-white/60 bg-brand-900 px-2 py-1 text-white"
        onChange={async (event) => {
          setBusy(true);
          const { response } = await browserApi
            .POST("/auth/workspace", { body: { workspaceId: event.target.value } })
            .catch(() => ({ response: null }));
          setBusy(false);
          if (response?.ok) {
            router.push("/inbox");
            router.refresh();
          }
        }}
      >
        {workspaces.map((w) => (
          <option key={w.id} value={w.id}>
            {w.name}
          </option>
        ))}
      </select>
    </label>
  );
}
