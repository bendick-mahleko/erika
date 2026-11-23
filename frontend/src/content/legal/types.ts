/**
 * A legal text shown in the app (ADR 0030). German only: these documents are binding in
 * German. `draft` shows a notice until a lawyer has reviewed the text (Stage 11, NF-09);
 * set it to false only after that review.
 */
export type LegalDocument = {
  title: string;
  /** ISO date of the last change. */
  updated: string;
  draft: boolean;
  /** Markdown subset, see components/Markdown.tsx. Placeholders in [eckigen Klammern]. */
  body: string;
};
