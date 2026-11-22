"use client";

import { useRouter } from "next/navigation";
import { browserApi } from "@/lib/api/browser";

export function LogoutButton({ label }: { label: string }) {
  const router = useRouter();
  return (
    <button
      type="button"
      className="rounded border border-white/60 px-3 py-1 text-white hover:bg-white/10"
      onClick={async () => {
        await browserApi.POST("/auth/logout").catch(() => undefined);
        router.push("/login");
        router.refresh();
      }}
    >
      {label}
    </button>
  );
}
