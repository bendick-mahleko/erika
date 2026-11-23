"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

/** Reloads the page every 1.5 s while the worker issues the invoice (number, file, validation, archive). */
export function IssuingWatcher({ message }: { message: string }) {
  const router = useRouter();
  useEffect(() => {
    const timer = setInterval(() => router.refresh(), 1500);
    return () => clearInterval(timer);
  }, [router]);
  return (
    <p role="status" aria-live="polite" className="text-slate-700" data-testid="issuing">
      {message}
    </p>
  );
}
