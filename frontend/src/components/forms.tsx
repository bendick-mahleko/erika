import type { InputHTMLAttributes, ReactNode } from "react";

/** Labelled input with optional hint; the hint is announced with the field (WCAG 1.3.1, 3.3.2). */
export function Field({
  id,
  label,
  hint,
  ...input
}: { id: string; label: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const hintId = hint ? `${id}-hint` : undefined;
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block font-medium">
        {label}
      </label>
      <input
        id={id}
        name={id}
        aria-describedby={hintId}
        className="block w-full rounded border border-slate-400 px-3 py-2 disabled:bg-slate-100 disabled:text-slate-600"
        {...input}
      />
      {hint && (
        <p id={hintId} className="text-sm text-slate-600">
          {hint}
        </p>
      )}
    </div>
  );
}

export function ErrorAlert({ message }: { message: string | null }) {
  // Always rendered so screen readers announce changes to the live region.
  return (
    <div
      role="alert"
      aria-live="assertive"
      className={message ? "rounded border border-red-700 bg-red-50 px-4 py-3 text-red-900" : ""}
    >
      {message}
    </div>
  );
}

export function SubmitButton({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <button
      type="submit"
      disabled={busy}
      aria-busy={busy}
      className="rounded bg-brand-700 px-4 py-2 font-medium text-white hover:bg-brand-900 disabled:opacity-60"
    >
      {children}
    </button>
  );
}

export function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-labelledby="auth-title" className="mx-auto max-w-md rounded-lg border border-slate-200 p-6 shadow-sm">
      <h1 id="auth-title" className="mb-4 text-2xl font-semibold">
        {title}
      </h1>
      {children}
    </section>
  );
}
