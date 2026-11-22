"use client";

import { useTranslations } from "next-intl";

/** Text for a problem `code` (Errors.* in messages); unknown codes get the generic text. */
export function useErrorText(): (code: string) => string {
  const te = useTranslations("Errors");
  return (code) => (te.has(code) ? te(code) : te("generic"));
}
