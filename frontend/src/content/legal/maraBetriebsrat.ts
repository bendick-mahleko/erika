import type { LegalDocument } from "./types";

/** M-BR-09: shown to the owner before MARA starts (trial or booking); confirmed on the billing page. */
export const maraBetriebsrat: LegalDocument = {
  title: "MARA: Hinweis zum Betriebsrat",
  updated: "2026-11-27",
  draft: true,
  body: `
Mit MARA erfassen Ihre Beschäftigten Arbeits- und Fahrtzeiten, Fotos und Berichte in einer App. Solche technischen Einrichtungen können geeignet sein, Verhalten oder Leistung der Beschäftigten zu überwachen.

## Wenn es in Ihrem Betrieb einen Betriebsrat gibt

Die Einführung und Anwendung von MARA kann der Mitbestimmung des Betriebsrats unterliegen (§ 87 Abs. 1 Nr. 6 BetrVG). Beteiligen Sie den Betriebsrat vor dem Einsatz, in der Regel mit einer Betriebsvereinbarung. Dieser Hinweis ersetzt keine Rechtsberatung.

## Was MARA tut und was nicht

- MARA speichert die Zeiten, die Beschäftigte selbst erfassen, zu ihren Aufträgen. Es gibt keine Standortverfolgung und keine Aufzeichnung im Hintergrund.
- Zeiten sehen die Beschäftigten selbst (für ihre Einsätze), Inhaber und Büro. Steuerberater sehen sie nicht.
- MARA bildet keine Ranglisten, Leistungswerte oder Vergleiche zwischen Beschäftigten.
- Die Zeiten auf einem Arbeitsbericht sieht auch der Auftraggeber, der den Bericht unterschreibt.
- Die App sendet zur Fehlersuche nur Zähler und Absturzberichte ohne Inhalte.

## Ihre Pflichten gegenüber den Beschäftigten

Informieren Sie Ihre Beschäftigten vor dem ersten Einsatz über die Verarbeitung ihrer Daten (Art. 13 DSGVO). Dafür können Sie das [Informationsblatt für Beschäftigte](/mara/mitarbeiterinformation) verwenden und um die Angaben zu Ihrem Unternehmen ergänzen.
`,
};
