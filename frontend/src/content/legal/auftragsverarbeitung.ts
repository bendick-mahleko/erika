import type { LegalDocument } from "./types";

export const auftragsverarbeitung: LegalDocument = {
  title: "Vertrag zur Auftragsverarbeitung (Art. 28 DSGVO)",
  updated: "2026-11-23",
  draft: true,
  body: `
zwischen dem Kunden (Verantwortlicher, „Kunde“) und [Firmenname] (Auftragsverarbeiter, „wir“). Der Vertrag wird mit der Annahme der [Nutzungsbedingungen](/nutzungsbedingungen) bei der Registrierung geschlossen.

## 1. Gegenstand und Dauer

Wir verarbeiten personenbezogene Daten, die in den Rechnungen und Dokumenten des Kunden enthalten sind, ausschließlich zur Erbringung von ERIKA: Empfang, Virenprüfung, Prüfung, Anzeige, Archivierung und – auf Veranlassung des Kunden – Export. Der Vertrag gilt für die Dauer der Nutzung von ERIKA und darüber hinaus, solange wir Daten des Kunden aufbewahren.

## 2. Art der Daten und Betroffene

- **Daten:** Namen, Anschriften, Kontaktdaten, Steuernummern und Umsatzsteuer-Identifikationsnummern, Bankverbindungen, Rechnungsinhalte (Positionen, Beträge, Daten), Inhalte und Absender von E-Mails an die Eingangsadresse des Kunden.
- **Betroffene:** Geschäftspartner des Kunden (Lieferanten, Kunden) und deren Ansprechpartner; Beschäftigte des Kunden, die ERIKA nutzen.

## 3. Weisungen

Wir verarbeiten die Daten nur auf dokumentierte Weisung des Kunden. Die Weisungen ergeben sich aus diesem Vertrag und der Nutzung der Funktionen von ERIKA. Halten wir eine Weisung für rechtswidrig, informieren wir den Kunden unverzüglich.

## 4. Vertraulichkeit

Personen, die bei uns Zugang zu den Daten haben, sind zur Vertraulichkeit verpflichtet. Zugriffe auf Kundendaten durch uns erfolgen nur, soweit sie für den Betrieb, die Fehlerbehebung oder auf Bitte des Kunden erforderlich sind.

## 5. Technische und organisatorische Maßnahmen

Wir treffen die Maßnahmen nach Art. 32 DSGVO, die in Anlage 1 beschrieben sind. Wir dürfen sie weiterentwickeln, solange das Schutzniveau nicht sinkt.

## 6. Unterauftragsverarbeiter

Der Kunde genehmigt die in der [Liste der Unterauftragsverarbeiter](/unterauftragsverarbeiter) genannten Dienstleister. Über beabsichtigte Änderungen informieren wir mindestens vier Wochen vorher per E-Mail; der Kunde kann aus wichtigem datenschutzrechtlichen Grund widersprechen. Unterauftragsverarbeitern erlegen wir dieselben Pflichten auf wie in diesem Vertrag. Eine Verarbeitung außerhalb der EU findet nicht statt.

## 7. Unterstützung des Kunden

Wir unterstützen den Kunden bei Anfragen betroffener Personen (Art. 15–22 DSGVO), insbesondere durch Export- und Suchfunktionen, sowie bei Datenschutz-Folgenabschätzungen und Konsultationen. Wenden sich Betroffene direkt an uns, leiten wir die Anfrage an den Kunden weiter.

## 8. Verletzungen des Schutzes personenbezogener Daten

Wir melden dem Kunden eine Verletzung unverzüglich, möglichst innerhalb von 24 Stunden nach Kenntnis, mit den Angaben nach Art. 33 Abs. 3 DSGVO, soweit sie uns vorliegen.

## 9. Ende des Auftrags

Nach Ende der Nutzung stellen wir dem Kunden die Originale und Daten zum Herunterladen bereit. Auf Weisung des Kunden bewahren wir die Originale bis zum Ende der gesetzlichen Aufbewahrungsfrist unveränderbar auf oder löschen sie, sobald die technische Sperre (Object Lock) dies zulässt. Sicherungskopien werden nach spätestens 35 Tagen überschrieben.

## 10. Nachweise und Kontrollen

Wir stellen dem Kunden die zum Nachweis der Pflichten erforderlichen Informationen zur Verfügung, insbesondere die Verfahrensdokumentation und Berichte der Wiederherstellungstests. Kontrollen vor Ort sind nach Absprache mit angemessener Vorankündigung möglich.

## Anlage 1: Technische und organisatorische Maßnahmen

### Vertraulichkeit

- Betrieb ausschließlich in Rechenzentren in Deutschland (Hetzner, zertifiziert nach ISO/IEC 27001); physischer Zutritt durch den Rechenzentrumsbetreiber kontrolliert.
- Zwei-Faktor-Anmeldung für alle Nutzer; Passwörter nur als Argon2-Hash; Sperre nach wiederholten Fehlversuchen; Benachrichtigung bei Anmeldung von neuen Geräten.
- Mandantentrennung in der Datenbank durch Row-Level Security; bei jedem Software-Build automatisch geprüft. Rollen im Arbeitsbereich; Steuerberater haben nur Lesezugriff.
- Die Datenbank hat keine öffentliche IP-Adresse; Administratorzugang nur per SSH-Schlüssel von festen Adressen.
- Verschlüsselte Übertragung (TLS 1.2 oder höher), auch zwischen den Servern; verschlüsselter Datenträger der Datenbank; Sicherungen mit AES-256 verschlüsselt.

### Integrität

- Originale werden beim Eingang mit SHA-256 erfasst und mit S3 Object Lock (Modus COMPLIANCE) bis zum Ende der Aufbewahrungsfrist gegen Änderung und Löschung gesperrt; die Prüfsumme wird bei jedem Abruf und regelmäßig stichprobenartig geprüft.
- Änderungen im Arbeitsbereich werden in einem hash-verketteten Audit-Protokoll festgehalten, das täglich geprüft wird.
- Jede empfangene Datei wird vor der Ablage auf Schadsoftware geprüft.
- Protokolle enthalten keine Rechnungsinhalte; IP-Adressen werden nach 7 Tagen gekürzt.

### Verfügbarkeit und Belastbarkeit

- Fortlaufende Sicherung der Datenbank (Änderungen höchstens 5 Minuten verzögert), tägliche Vollsicherung, Aufbewahrung 35 Tage, an einem zweiten Standort.
- Tägliche Kopie der Originale an den zweiten Standort.
- Überwachung rund um die Uhr mit Alarmierung (Erreichbarkeit, Fehlerraten, Sicherungen, Zertifikate, Audit-Protokoll).
- Monatlicher Wiederherstellungstest mit Zeitmessung; Ziel: Wiederherstellung innerhalb von 4 Stunden.

### Verfahren zur regelmäßigen Überprüfung

- Automatisierte Tests bei jeder Änderung, einschließlich Mandantentrennung und Sicherheitsprüfungen der Abhängigkeiten.
- Release-Checkliste und dokumentierte Betriebsabläufe (Runbooks); Überprüfung dieser Maßnahmen mindestens jährlich.
`,
};
