import type { LegalDocument } from "./types";

export const datenschutz: LegalDocument = {
  title: "Datenschutzhinweise",
  updated: "2026-11-27",
  draft: true,
  body: `
Diese Hinweise erklären, welche personenbezogenen Daten ERIKA verarbeitet, wofür und welche Rechte Sie haben (Art. 13 DSGVO).

## 1. Verantwortlicher

[Firmenname, Anschrift, E-Mail – wie im [Impressum](/impressum)]

Für Fragen zum Datenschutz: [datenschutz@erika-app.de]

## 2. Zwei Rollen: Ihr Konto und Ihre Rechnungen

- **Ihr Konto** (Name, E-Mail-Adresse, Anmeldung): Hierfür sind wir Verantwortlicher im Sinne der DSGVO.
- **Ihre Rechnungen und Dokumente** in Ihrem Arbeitsbereich: Diese verarbeiten wir in Ihrem Auftrag als Auftragsverarbeiter. Verantwortlich sind Sie; es gilt der [Vertrag zur Auftragsverarbeitung](/auftragsverarbeitung).

## 3. Welche Daten wir verarbeiten

- **Kontodaten:** Name, E-Mail-Adresse, Sprache, Passwort (nur als Argon2-Hash), Schlüssel für die Zwei-Faktor-Anmeldung (verschlüsselt), Wiederherstellungscodes (nur als Hash), Zeitpunkt der Annahme der Nutzungsbedingungen.
- **Unternehmensdaten:** Firmenname, Anschrift, Umsatzsteuer-Identifikationsnummer, Steuernummer.
- **Sicherheits- und Protokolldaten:** Anmeldungen und Fehlversuche, bekannte Geräte (als Hash), Änderungen in Ihrem Arbeitsbereich (Audit-Protokoll), technische Server-Protokolle mit IP-Adresse und Zeitpunkt. Die Protokolle enthalten keine Rechnungsinhalte.
- **Feedback:** Nachrichten, die Sie uns über die Feedback-Funktion schicken, mit der Seite, auf der Sie waren.
- **Technische Daten der MARA-App:** Absturzberichte (Art des Fehlers, Programmstelle, App-Version, Gerätemodell und Betriebssystem, ohne Inhalte, Namen oder Eingaben) und je Gerät und Tag Zähler, wie Synchronisierungen und Foto-Uploads ausgegangen sind (nur Zahlen, ohne Inhalte). Wir nutzen sie, um Fehler zu finden und die Zuverlässigkeit der Synchronisierung zu prüfen.

## 4. Zwecke und Rechtsgrundlagen

- Bereitstellung von ERIKA und Ihres Kontos: Vertrag (Art. 6 Abs. 1 lit. b DSGVO).
- Schutz vor Missbrauch, Sicherheit der Anmeldung, Fehleranalyse: berechtigtes Interesse an einem sicheren Betrieb (Art. 6 Abs. 1 lit. f DSGVO).
- Erfüllung gesetzlicher Pflichten, z. B. Aufbewahrung unserer eigenen Geschäftsunterlagen: Art. 6 Abs. 1 lit. c DSGVO.
- Auswertung Ihres Feedbacks zur Verbesserung von ERIKA während der Beta: Vertrag und berechtigtes Interesse (Art. 6 Abs. 1 lit. b und f DSGVO).

Die Angabe Ihrer Kontodaten ist für die Nutzung erforderlich; ohne sie können wir kein Konto einrichten. Eine automatisierte Entscheidungsfindung oder Profilbildung findet nicht statt.

## 5. Cookies

ERIKA setzt nur technisch notwendige Cookies (§ 25 Abs. 2 Nr. 2 TDDDG): die Sitzung (ERIKA_SESSION), den Schutz vor gefälschten Anfragen (XSRF-TOKEN) und die Wiedererkennung bekannter Geräte für Sicherheitshinweise (erika_device). Wir verwenden keine Analyse-, Werbe- oder Tracking-Werkzeuge.

## 6. Empfänger

Wir setzen Dienstleister ein, die Daten in unserem Auftrag verarbeiten; sie sind in der [Liste der Unterauftragsverarbeiter](/unterauftragsverarbeiter) genannt. Alle Daten und Sicherungen liegen in Rechenzentren in Deutschland. Eine Übermittlung in Länder außerhalb der EU findet nicht statt. Fehlerberichte und Überwachung betreiben wir selbst auf unseren Servern.

## 7. Speicherdauer

- Kontodaten: bis zur Schließung des Kontos; danach, soweit gesetzliche Aufbewahrungspflichten bestehen, bis zu deren Ablauf.
- Server-Protokolle: vollständige IP-Adressen 7 Tage, danach gekürzt; Löschung nach 14 Tagen.
- Fehlerberichte (auch Absturzberichte der MARA-App): 30 Tage.
- Zähler der MARA-App zu Synchronisierung und Uploads: 180 Tage.
- Datensicherungen: 35 Tage.
- Audit-Protokoll: für die Dauer der Aufbewahrungsfrist der zugehörigen Unterlagen, weil es deren Unveränderbarkeit belegt.
- Feedback: bis zum Ende der Beta; danach löschen oder anonymisieren wir es, soweit wir es nicht für eine laufende Rückfrage brauchen.

## 8. Ihre Rechte

Sie haben das Recht auf Auskunft (Art. 15 DSGVO), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18), Datenübertragbarkeit (Art. 20) und Widerspruch gegen Verarbeitungen auf Grundlage berechtigter Interessen (Art. 21). Schreiben Sie dazu an die oben genannte Adresse; wir antworten innerhalb eines Monats.

Sie können sich bei einer Datenschutz-Aufsichtsbehörde beschweren, insbesondere bei der für unseren Sitz zuständigen: [zuständige Landesbehörde, Anschrift].

## 9. Sicherheit

Verbindungen sind mit TLS verschlüsselt, die Anmeldung ist mit einem zweiten Faktor geschützt, Sicherungen sind verschlüsselt und liegen an einem zweiten Standort. Einzelheiten stehen in Anlage 1 des [Vertrags zur Auftragsverarbeitung](/auftragsverarbeitung).
`,
};
