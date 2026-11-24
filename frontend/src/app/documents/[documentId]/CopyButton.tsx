"use client";

import { useState } from "react";

/** Copies one value to the clipboard and says so in a live region. */
export function CopyButton({ value, label, copied }: { value: string; label: string; copied: string }) {
  const [done, setDone] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setDone(true);
      setTimeout(() => setDone(false), 2000);
    } catch {
      setDone(false);
    }
  }

  return (
    <span className="inline-flex items-center gap-2">
      <button
        type="button"
        onClick={copy}
        aria-label={label}
        className="rounded border border-slate-400 px-2 py-0.5 text-sm hover:bg-slate-50"
      >
        <span aria-hidden="true">⧉</span>
      </button>
      <span role="status" className="text-sm text-green-800">
        {done ? copied : ""}
      </span>
    </span>
  );
}
