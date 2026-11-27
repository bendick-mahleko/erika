import type { LegalDocument } from "./types";

/** NF-01: sub-processor list, published. Change it only together with docs/legal/README.md. */
export const unterauftragsverarbeiter: LegalDocument = {
  title: "Unterauftragsverarbeiter",
  updated: "2026-11-27",
  draft: true,
  body: `
Diese Dienstleister verarbeiten personenbezogene Daten in unserem Auftrag. Mit jedem besteht ein Vertrag zur Auftragsverarbeitung nach Art. 28 DSGVO. Alle gespeicherten Daten bleiben in der EU, die Rechenzentren liegen in Deutschland. Nur für Push-Nachrichten an die MARA-App werden Geräte-Kennungen an Google und Apple übermittelt (siehe unten). Änderungen kündigen wir mindestens vier Wochen vorher an.

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

## Google Ireland Limited (Firebase Cloud Messaging) – nur mit MARA

Gordon House, Barrow Street, Dublin 4, Irland

- **Leistung:** Zustellung von Push-Nachrichten an die MARA-App („bitte synchronisieren“), auch an iPhones über Apple
- **Standort:** EU; Übermittlung in die USA möglich (Google LLC), auf Grundlage des EU-U.S. Data Privacy Framework und von Standardvertragsklauseln
- **Daten:** die Push-Kennung des Geräts und die Nachricht selbst, die keine personenbezogenen Daten und keine Inhalte enthält (nur die Aufforderung „bitte synchronisieren“)

## Apple Distribution International Ltd. (Apple Push Notification service) – nur mit MARA

Hollyhill Industrial Estate, Hollyhill, Cork, Irland

- **Leistung:** Zustellung der Push-Nachrichten an iPhones
- **Standort:** EU; Übermittlung in die USA möglich (Apple Inc.), auf Grundlage des EU-U.S. Data Privacy Framework und von Standardvertragsklauseln
- **Daten:** die Push-Kennung des Geräts und die inhaltslose Nachricht

## Nicht auf dieser Liste

Fehlerberichte (auch Absturzberichte der MARA-App), Überwachung, Protokolle und die Statusseite betreiben wir selbst auf unseren Servern bei Hetzner. Die App-Stores von Google und Apple, über die Beschäftigte die MARA-App installieren, sind keine Auftragsverarbeiter von ERIKA; dafür gelten deren eigene Bedingungen. Eingehende Rechnungs-E-Mails empfängt ERIKA mit einem eigenen Mailserver. Analyse- oder Werbedienste setzen wir nicht ein.
`,
};
