import type { LegalDocument } from "./types";

/** M-BR-09, Art. 13 DSGVO: what the employer hands to employees who use the MARA app. */
export const maraMitarbeiterinformation: LegalDocument = {
  title: "MARA: Informationen für Beschäftigte",
  updated: "2026-11-27",
  draft: true,
  body: `
Ihr Arbeitgeber nutzt MARA für Aufträge und Arbeitsberichte. Diese Seite erklärt, welche Daten über Sie dabei verarbeitet werden. Verantwortlich ist Ihr Arbeitgeber: [Name und Anschrift des Arbeitgebers, Kontakt des Datenschutzbeauftragten, falls vorhanden]. ERIKA verarbeitet die Daten in seinem Auftrag.

## Welche Daten

- Ihr Name, Ihre E-Mail-Adresse und Ihre Rolle im Unternehmen.
- Die Aufträge, die Ihnen zugewiesen sind, und die Berichte, die Sie dazu schreiben: Tätigkeiten, Material, Aufmaß, Checklisten, Fotos vom Einsatzort.
- Arbeits- und Fahrtzeiten, die Sie selbst erfassen (Beginn, Ende, Notiz).
- Ihr Gerät: Modell, Betriebssystem, App-Version, Zeitpunkt der letzten Synchronisierung; eine Push-Kennung, damit die App über Änderungen informiert werden kann.
- Technische Zähler ohne Inhalte (wie oft die Synchronisierung gelang) und Absturzberichte ohne Inhalte.

## Was nicht

- Kein Standort, keine Bewegungsprofile, keine Aufzeichnung im Hintergrund.
- Keine Ranglisten, Leistungswerte oder Vergleiche mit Kolleginnen und Kollegen.
- Keine Fotos aus Ihrer Galerie: Fotos in MARA bleiben in der App.

## Wer die Daten sieht

- Ihre Zeiten: Sie selbst, Inhaber und Büro Ihres Arbeitgebers. Kolleginnen und Kollegen sehen sie nur auf Berichten gemeinsamer Einsätze. Der Auftraggeber sieht die Zeiten auf dem Bericht, den er unterschreibt.
- Steuerberater Ihres Arbeitgebers sehen Rechnungen und Berichte, aber keine Zeitaufstellungen.

## Wofür

Für die Durchführung und Dokumentation der Aufträge, die Abrechnung mit dem Auftraggeber und – wenn Ihr Arbeitgeber das nutzt – für die Lohnabrechnung (§ 26 BDSG, Art. 6 Abs. 1 lit. b und c DSGVO).

## Wie lange

Arbeitsberichte und Rechnungen bewahrt Ihr Arbeitgeber nach den gesetzlichen Fristen auf (bis zu 8 Jahre bzw. 10 Jahre). Zeiten werden nach [Frist, Standard 2 Jahre] gelöscht, wenn sie nicht für Rechnungen oder die Lohnabrechnung länger gebraucht werden. Fotos: [Frist, Standard 5 Jahre nach dem Auftrag].

## Ihr Gerät

Wird Ihr Gerät verloren oder gestohlen, kann Ihr Arbeitgeber es sperren; die App löscht dann alle Daten auf dem Gerät. Die Daten auf dem Gerät sind verschlüsselt und nur mit Ihrer PIN oder Ihrem Fingerabdruck zugänglich.

## Ihre Rechte

Sie haben das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung und Widerspruch (Art. 15–21 DSGVO). Wenden Sie sich dazu an Ihren Arbeitgeber. Sie können sich bei einer Datenschutz-Aufsichtsbehörde beschweren.
`,
};
