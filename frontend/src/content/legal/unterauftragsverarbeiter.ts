import type { LegalDocument } from "./types";

/** NF-01: sub-processor list, published. Change it only together with docs/legal/README.md. */
export const unterauftragsverarbeiter: LegalDocument = {
  title: "Unterauftragsverarbeiter",
  updated: "2026-11-23",
  draft: true,
  body: `
Diese Dienstleister verarbeiten personenbezogene Daten in unserem Auftrag. Mit jedem besteht ein Vertrag zur Auftragsverarbeitung nach Art. 28 DSGVO. Alle Daten bleiben in der EU, die Rechenzentren liegen in Deutschland. Änderungen kündigen wir mindestens vier Wochen vorher an.

## Hetzner Online GmbH

Industriestr. 25, 91710 Gunzenhausen, Deutschland

- **Leistung:** Rechenzentrum, Server, Objektspeicher (Originale, Sicherungen)
- **Standorte:** Nürnberg und Falkenstein (Deutschland)
- **Daten:** alle in ERIKA gespeicherten Daten, verschlüsselt übertragen; Sicherungen zusätzlich von uns verschlüsselt

## [E-Mail-Versanddienst]

[Anschrift]

- **Leistung:** Versand von System-E-Mails (Bestätigung der E-Mail-Adresse, Sicherheitshinweise, Benachrichtigungen über neue Dokumente)
- **Standort:** [EU-Land]
- **Daten:** E-Mail-Adresse, Name und Inhalt dieser System-E-Mails; keine Rechnungen

## Nicht auf dieser Liste

Fehlerberichte, Überwachung, Protokolle und die Statusseite betreiben wir selbst auf unseren Servern bei Hetzner. Eingehende Rechnungs-E-Mails empfängt ERIKA mit einem eigenen Mailserver. Analyse- oder Werbedienste setzen wir nicht ein.
`,
};
