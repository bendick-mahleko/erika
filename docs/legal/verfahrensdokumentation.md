# Verfahrensdokumentation ERIKA – erster Entwurf

Stand: 23.11.2026 (ergänzt um Ausgangsrechnungen, Stage 8) · Status: **Entwurf** (Software Design, offener Punkt 4). Prüfung mit einer Steuerberaterin bzw. einem Steuerberater vor dem Start (Stage 11).

Diese Dokumentation beschreibt, wie ERIKA elektronische Rechnungen empfängt, prüft, archiviert und bereitstellt. Sie folgt dem Aufbau der GoBD (BMF-Schreiben, Rz. 151 ff.) und ist als **Baustein für die Verfahrensdokumentation unserer Kunden** gedacht: Jeder Kunde ergänzt sie um seine eigenen organisatorischen Regeln (wer prüft, wer gibt frei, wer bucht).

## 1. Allgemeine Beschreibung

### 1.1 Zweck und Umfang

ERIKA ist eine Web-Anwendung für kleine Unternehmen. Sie

- empfängt Eingangsrechnungen per Upload oder E-Mail an eine persönliche Eingangsadresse,
- erkennt das Format (XRechnung UBL/CII, ZUGFeRD/Factur-X, sonstige PDF),
- prüft E-Rechnungen gegen die amtlichen Regeln (KoSIT-Prüfkonfiguration, EN 16931),
- archiviert das Original unveränderbar bis zum Ende der Aufbewahrungsfrist,
- stellt Rechnungen lesbar dar und verwaltet einen Bearbeitungsstatus,
- erstellt Ausgangsrechnungen, Gutschriften und Stornorechnungen als E-Rechnung (XRechnung oder ZUGFeRD), vergibt fortlaufende Nummern und versendet sie per E-Mail.

Das **Original** ist die empfangene Datei (bei E-Rechnungen die XML-Datei bzw. das hybride PDF). Lesefassungen (HTML-Ansicht, PDF-Ansichtskopie) sind abgeleitet und als solche gekennzeichnet.

### 1.2 Beteiligte und Verantwortung

- **Kunde:** Verantwortlich für die Ordnungsmäßigkeit seiner Buchführung, die Vollständigkeit der Belege, die Rollenvergabe in seinem Arbeitsbereich und seine organisatorischen Abläufe.
- **ERIKA (Betreiber):** Betrieb der Anwendung, Archivierung, Datensicherung und Protokollierung nach dieser Dokumentation; Auftragsverarbeiter nach Art. 28 DSGVO.
- **Rechenzentrum:** Hetzner Online GmbH, Standorte Nürnberg und Falkenstein (Deutschland).

## 2. Anwenderdokumentation

### 2.1 Eingang

1. **Upload** im Browser (einzelne Dateien oder ZIP) oder **E-Mail** an die Eingangsadresse des Arbeitsbereichs.
2. Bei E-Mail wird zuerst die vollständige Nachricht (.eml) archiviert, danach werden die Anhänge verarbeitet.
3. Jede Datei wird auf Schadsoftware geprüft. Schlägt die Prüfung fehl oder ist sie nicht möglich, wird die Datei nicht übernommen (Ergebnis im Eingangsprotokoll sichtbar).
4. Zulässige Dateien (XML, PDF) werden archiviert und als Dokument im Eingang angezeigt. ZIP-Archive werden entpackt; jede enthaltene Datei wird einzeln behandelt.
5. Eine bitgleiche Datei, die bereits archiviert ist, wird nicht doppelt abgelegt; der Eingang zeigt „Mögliches Duplikat“. Rechnungen mit gleicher Steuernummer des Lieferanten, Rechnungsnummer und gleichem Bruttobetrag werden ebenfalls als mögliches Duplikat gekennzeichnet.

### 2.2 Prüfung und Anzeige

- Ergebnis je E-Rechnung: grün (gültig), gelb (gültig mit Hinweisen), rot (fehlerhaft), mit verständlicher Erklärung je Regel. Das angewandte Regelwerk (Version) wird mit dem Ergebnis gespeichert.
- „Erneut prüfen“ erzeugt ein weiteres Prüfergebnis mit dem aktuellen Regelwerk; frühere Ergebnisse bleiben erhalten.
- Die Ansicht zeigt jedes Geschäftsfeld der Rechnung mit deutscher Bezeichnung. Die PDF-Ansichtskopie trägt auf jeder Seite den Vermerk „Ansichtskopie – Original ist die XML-Datei“.

### 2.3 Bearbeitungsstatus

Status: Neu, Geprüft, Freigegeben, Bezahlt, Beanstandet, Gebucht; dazu Schlagwörter und eine Notiz. Jede Änderung wird mit Benutzer, Zeitpunkt und Notiz im Verlauf und im Audit-Protokoll festgehalten. **Das Original ändert sich dabei nie.**

### 2.4 Rollen

Inhaber (alle Rechte, auch Rechnungseinstellungen, Einladungen, Rollen und Kontoschließung), Mitarbeiter (Bearbeitung, Rechnungen erstellen und versenden), Steuerberater (lesen, kommentieren und exportieren, aber nichts ändern). Mitglieder werden per E-Mail eingeladen; der Link gilt 7 Tage und nur für die eingeladene Adresse. Rollenänderungen und Entfernungen wirken sofort: Die betroffene Person muss sich neu anmelden. Jede Anmeldung erfordert einen zweiten Faktor. Einladungen, Rollenänderungen und Entfernungen stehen im Audit-Protokoll.

### 2.5 Ausgangsrechnungen

1. Rechnungen entstehen als **Entwurf** aus gespeicherten Kunden und Artikeln oder aus freien Angaben. Der Entwurf wird beim Bearbeiten gespeichert und kann bis zur Ausstellung geändert oder verworfen werden.
2. ERIKA prüft beim Bearbeiten und vor der Ausstellung die Pflichtangaben nach § 14 Abs. 4 UStG und EN 16931 (u. a. vollständige Anschriften, Steuernummer oder USt-IdNr., Leistungsdatum oder -zeitraum, Menge, Bezeichnung, Steuersatz). Fehlt etwas, kann die Rechnung nicht ausgestellt werden.
3. **Ausstellung:** In einem Vorgang vergibt ERIKA die nächste Nummer nach dem eingestellten Nummernformat (fortlaufend je Format und – falls enthalten – je Jahr), erzeugt die Datei, prüft sie mit den amtlichen Regeln (wie beim Eingang, 2.2) und archiviert sie unveränderbar (3.2). Findet die Prüfung Fehler, wird nichts ausgestellt und die Nummer nicht verbraucht. Ausgestellte Nummern bleiben dauerhaft im Bestand, auch nach einer Stornierung.
4. Die Umsatzsteuer wird je Position nach dem gewählten Fall ausgewiesen: 19 %, 7 %, 0 %, Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG), steuerfreie innergemeinschaftliche Lieferung (§ 4 Nr. 1 Buchst. b, § 6a UStG) oder Kleinunternehmer (§ 19 UStG, ohne Steuerausweis). Die vorgeschriebenen Hinweise werden automatisch auf die Rechnung gesetzt.
5. **Korrekturen** erfolgen nie durch Ändern, sondern durch eine Gutschrift, eine Stornorechnung oder eine korrigierte Rechnung, die auf die ursprüngliche Rechnung verweist (Nummer und Datum). Bei Stornierung oder Korrektur erhält die ursprüngliche Rechnung den Status „Storniert“.
6. **Versand:** Beim Versand per E-Mail archiviert ERIKA die gesendete Nachricht mit der angehängten Rechnungsdatei unveränderbar und protokolliert Zeitpunkt, Empfänger und Zustellergebnis. Die Rechnung kann auch heruntergeladen und auf anderem Weg übermittelt werden.
7. Status ausgehender Rechnungen: Ausgestellt, Versendet, Zustellung fehlgeschlagen, Bezahlt, Storniert, Gebucht; jede Änderung mit Benutzer und Zeitpunkt im Verlauf und Audit-Protokoll.

### 2.6 Abruf und Export

Das Original kann jederzeit unverändert heruntergeladen werden; die Prüfsumme (SHA-256) wird dabei geprüft und mitgeliefert.

**Zeitraum-Export für die Steuerberatung:** eine ZIP-Datei mit den Originalen des Zeitraums (maßgeblich ist das Rechnungsdatum, sonst das Eingangsdatum), einer Übersicht (`index.csv` mit Dateiname, SHA-256 und Rechnungsdaten) und einem DATEV-Buchungsstapel (Format EXTF, nicht festgeschrieben) je Wirtschaftsjahr. Der Buchungsstapel ist ein Vorschlag: eine Buchung je Rechnung und Steuersatz gegen Kreditor bzw. Debitor und ein Aufwands- bzw. Erlöskonto ohne Automatikfunktion; Steuerschlüssel nur für 19 % und 7 %. Die Kanzlei prüft und kontiert vor dem Festschreiben.

**Vollständiger Kontoexport** (nur Inhaber): alle archivierten Originale, alle Daten als JSON, das Audit-Protokoll mit Hash-Kette und eine Prüfsummenliste (`MANIFEST.sha256`). Er eignet sich auch für den Datenzugriff der Finanzverwaltung (Z3, Datenträgerüberlassung).

Exporte werden im Hintergrund erstellt, nur nach Anmeldung heruntergeladen und nach 7 Tagen gelöscht. Anforderung und jeder Download stehen im Audit-Protokoll.

### 2.7 Abonnement und Zahlungsverzug

Nach der Testphase ist ein Abonnement nötig (Zahlung über Stripe per Karte oder SEPA-Lastschrift). Ohne Abonnement oder wenn die Zahlung endgültig scheitert, wird der Arbeitsbereich schreibgeschützt: Lesen, Herunterladen und Exportieren der Originale bleiben jederzeit möglich, die Aufbewahrung läuft unverändert weiter. Für jede Zahlung stellt ERIKA eine E-Rechnung (ZUGFeRD, geprüft wie jede ausgestellte Rechnung) aus und sendet sie per E-Mail und in den Eingang des Kunden. Statuswechsel des Abonnements und Zahlungen stehen im Audit-Protokoll.

### 2.8 Kündigung und Schließung des Kontos

Der Inhaber kann das Konto schließen. Danach ist der Arbeitsbereich schreibgeschützt: Lesen, Herunterladen und Exportieren bleiben möglich, Änderungen nicht; E-Mails an die Eingangsadresse werden abgewiesen. Die Daten bleiben bis zum Ende der längsten Aufbewahrungsfrist der archivierten Originale erhalten und werden danach gelöscht (docs/ops/gdpr.md). Die Schließung steht im Audit-Protokoll und kann in ERIKA nicht rückgängig gemacht werden.

## 3. Technische Systemdokumentation

### 3.1 Aufbau

Web-Oberfläche, Programmierschnittstelle (API) und Hintergrundverarbeitung laufen auf einem Anwendungsserver; die Datenbank (PostgreSQL) auf einem eigenen Server ohne öffentliche Adresse; Originale in einem S3-kompatiblen Objektspeicher mit Object Lock. Einzelheiten: Software Design §§ 3–8, ADR 0027.

### 3.2 Unveränderbarkeit der Originale (GoBD Rz. 58 ff., 107 ff.)

- Beim Eingang wird die SHA-256-Prüfsumme berechnet und gespeichert.
- Das Original wird im Objektspeicher mit **Object Lock im Modus COMPLIANCE** bis zum Ende der Aufbewahrungsfrist abgelegt: Bis dahin kann es niemand ändern oder löschen, auch nicht der Betreiber.
- Die Anwendung hat zusätzlich keine Löschberechtigung für diesen Speicher.
- Bei jedem Abruf wird die Prüfsumme neu berechnet und verglichen. Wöchentlich werden 1 % der Originale, jährlich alle Originale geprüft. Abweichungen lösen einen Alarm aus.
- **Aufbewahrungsfrist:** bis zum 31.12. des Jahres des Rechnungsdatums plus 8 Jahre (§ 147 AO, § 14b UStG in der Fassung des BEG IV), für Rechnungen vor dem 1.1.2025 plus 10 Jahre. Ohne Rechnungsdatum zählt das Eingangsdatum (mit der Steuerberatung zu bestätigen). Eine erneute Ablage derselben Datei kann die Frist nur verlängern, nie verkürzen (ADR 0022).

### 3.3 Protokollierung (GoBD Rz. 64)

Anmeldungen, Statusänderungen, Änderungen von Schlagwörtern und Notizen, Einstellungen, Kunden- und Artikeländerungen, die Ausstellung jeder Rechnung (mit Nummer und Prüfsumme), Versand und Zustellergebnisse sowie Exporte werden in einem **hash-verketteten Audit-Protokoll** je Arbeitsbereich festgehalten: Jeder Eintrag enthält den Hash seines Vorgängers. Täglich wird die Kette geprüft und ihr Endwert außerhalb der Datenbank gespeichert; eine Lücke oder Änderung löst einen Alarm aus (ADR 0019).

### 3.4 Mandantentrennung und Zugriffsschutz

Zeilenbasierte Zugriffsregeln der Datenbank (Row-Level Security) trennen die Arbeitsbereiche; automatisierte Tests prüfen das bei jeder Programmänderung. Rollen und zweiter Faktor wie unter 2.4.

### 3.5 Prüfregeln (Regelwerke)

Regelwerke sind versionierte Datenpakete mit Gültigkeitsdatum. Neue amtliche Versionen werden innerhalb von 30 Tagen bereitgestellt; alte bleiben erhalten, damit frühere Ergebnisse nachvollziehbar sind (Runbook validation-pack-update.md).

## 4. Betriebsdokumentation

### 4.1 Datensicherung

- Fortlaufende Sicherung der Datenbankänderungen (höchstens 5 Minuten Verzögerung) und tägliche Vollsicherung, 35 Tage aufbewahrt, verschlüsselt, an einem zweiten Standort.
- Tägliche Kopie der Originale und der Audit-Endwerte an den zweiten Standort; die Kopien sind 30 Tage gegen Löschung gesperrt.
- **Monatlicher Wiederherstellungstest** mit Protokoll und Zeitmessung (Ziel: 4 Stunden). Das Protokoll wird dem Kunden auf Anfrage bereitgestellt.

### 4.2 Änderungen an der Software

Jede Änderung durchläuft automatisierte Tests (u. a. amtliche Testdateien, Mandantentrennung, Sicherheitsprüfung) und wird über eine Release-Checkliste ausgeliefert. Datenbankänderungen sind abwärtskompatibel; ein Zurücksetzen auf die vorige Version ist jederzeit möglich. Versionsstände sind in der Versionsverwaltung nachvollziehbar.

### 4.3 Überwachung und Störungen

Überwachung rund um die Uhr (Erreichbarkeit, Fehlerraten, Warteschlangen, Sicherungen, Zertifikate, Audit-Kette) mit Alarmierung; öffentliche Statusseite. Störungen werden mit Ursache, Dauer und Auswirkung dokumentiert.

### 4.4 Zugriffe des Betreibers

Administratoren melden sich nur mit SSH-Schlüssel von festen Adressen an. Zugriffe auf Kundendaten erfolgen nur zur Störungsbehebung oder auf Bitte des Kunden und werden im Störungsprotokoll vermerkt.

## 5. Offene Punkte dieses Entwurfs

1. Formulierung zur GoBD-Konformität ohne Zertifizierung (Anforderungen, offene Frage 1).
2. Aufbewahrungsfrist und Fristbeginn bei fehlendem Rechnungsdatum mit der Steuerberatung bestätigen.
3. Ende der Aufbewahrungsfrist: Ablauf der Löschung nach Fristende (Löschkonzept, NF-09, Stage 9).
4. Verhalten gesperrter Objekte bei Vertragsende mit dem Speicheranbieter (ADR 0013).
5. Ergänzungen durch den Kunden: Wer prüft und gibt frei, wer bucht, Vertretungsregeln, Umgang mit Papierbelegen (Scannen ist nicht Teil von ERIKA).
