# ERIKA – Handbuch für Inhaber und Mitarbeiter

ERIKA hilft kleinen Betrieben, E-Rechnungen zu empfangen, zu prüfen, aufzubewahren und selbst zu schreiben. Dieses Handbuch erklärt die täglichen Abläufe. Es richtet sich an die **Inhaberin oder den Inhaber** eines Arbeitsbereichs und an **Mitarbeiterinnen und Mitarbeiter**. Für die Steuerberatung gibt es ein eigenes Handbuch ([steuerberatung.md](steuerberatung.md)).

> ERIKA ist derzeit in der geschlossenen Beta. Führen Sie Ihre bisherige Ablage während der Beta weiter (Beta-Nutzungsbedingungen § 5).

---

## 1. Das Wichtigste in Kürze

- **Eingang:** Rechnungen kommen per Upload oder per E-Mail an Ihre persönliche Eingangsadresse. ERIKA prüft jede Datei auf Schadsoftware, erkennt das Format und prüft E-Rechnungen gegen die amtlichen Regeln.
- **Archiv:** Jedes Original wird unveränderbar aufbewahrt, so lange es das Gesetz verlangt (in der Regel 8 Jahre ab Ende des Rechnungsjahres, für Rechnungen vor 2025 10 Jahre). Niemand kann es ändern oder löschen, auch Sie nicht.
- **Rechnungen schreiben:** ERIKA erstellt XRechnung oder ZUGFeRD, vergibt fortlaufende Nummern, prüft die Datei und versendet sie per E-Mail.
- **Export:** Zeitraum-Export mit DATEV-Buchungsstapel für die Steuerberatung, vollständiger Kontoexport für Sie.
- **Sicherheit:** Jede Anmeldung braucht Passwort **und** einen Code aus einer Authenticator-App.

## 2. Rollen

| Rolle | Darf |
|---|---|
| **Inhaber** | alles: Einstellungen, Rechnungseinstellungen, Team einladen, Rollen ändern, Absenderliste, Abonnement, Konto schließen |
| **Mitarbeiter** | Dokumente bearbeiten (Status, Notizen, Kommentare), Quarantäne entscheiden, Rechnungen schreiben, ausstellen und versenden, wiederkehrende Rechnungen, Exporte |
| **Steuerberater** | lesen, kommentieren, exportieren – sonst nichts ändern |

Jeder Arbeitsbereich hat mindestens einen Inhaber. Wer in mehreren Arbeitsbereichen Mitglied ist, wechselt oben in der Kopfzeile.

## 3. Erste Schritte

1. **Konto anlegen:** Öffnen Sie Ihren persönlichen Einladungslink (Beta). Geben Sie Name, Firma, E-Mail-Adresse und ein langes Passwort ein und akzeptieren Sie die Beta-Nutzungsbedingungen.
2. **E-Mail bestätigen:** Klicken Sie auf den Link in der E-Mail „Bitte bestätigen Sie Ihre E-Mail-Adresse“ (48 Stunden gültig).
3. **Zweiten Faktor einrichten:** Scannen Sie den QR-Code mit einer Authenticator-App (z. B. Google Authenticator, Microsoft Authenticator, 2FAS) und geben Sie den 6-stelligen Code ein.
4. **Wiederherstellungscodes sichern:** Drucken oder speichern Sie sie an einem sicheren Ort. Damit kommen Sie hinein, wenn Ihr Telefon verloren geht.
5. **Unternehmen eintragen:** *Einstellungen → Unternehmen*: Anschrift, USt-IdNr. oder Steuernummer.
6. **Eingangsadresse weitergeben:** Unter *Eingang* steht Ihre Adresse (`…@in.erika-app.de`). Geben Sie sie Ihren Lieferanten als Rechnungsadresse.

**Sprache:** ERIKA ist auf Deutsch. Mit **EN** oben rechts schalten Sie auf Englisch um; das gilt auch für Ihre E-Mails.

## 4. Rechnungen empfangen

### 4.1 Hochladen
*Eingang → Dateien auswählen* (oder hineinziehen). Erlaubt sind XML- und PDF-Dateien bis 20 MB sowie ZIP-Archive (bis 50 Dateien). Nach wenigen Sekunden erscheint jede Rechnung in der Liste.

### 4.2 Per E-Mail
Lieferanten senden an Ihre Eingangsadresse, die E-Mail darf bis 25 MB groß sein. ERIKA archiviert die ganze E-Mail und übernimmt jeden Anhang einzeln.

**Absenderliste (nur Inhaber):** Unter *Einstellungen → E-Mail-Eingang* können Sie festlegen, dass nur E-Mails von bekannten Absendern direkt übernommen werden. Tragen Sie dafür Adressen (`rechnung@lieferant.de`) oder ganze Domains (`@lieferant.de`) ein. E-Mails anderer Absender warten in der **Quarantäne**:
- **Übernehmen:** Die Rechnungen kommen in den Eingang.
- **Übernehmen und Absender erlauben:** Ebenso, und künftige E-Mails dieses Absenders kommen direkt.
- **Verwerfen:** Die Rechnungen werden nicht übernommen. Die E-Mail bleibt im Archiv.

### 4.3 Was ERIKA prüft
- **Schadsoftware:** Dateien mit Befund werden abgewiesen. Das sehen Sie im Eingangsprotokoll.
- **Duplikate:** Dieselbe Datei oder dieselbe Rechnung (gleicher Lieferant, gleiche Nummer, gleicher Betrag) wird als „Mögliches Duplikat“ markiert.
- **E-Rechnung ja/nein:** Ein reines PDF heißt „Keine E-Rechnung (sonstige Rechnung)“. Es wird trotzdem archiviert.
- **Amtliche Regeln:** Jede E-Rechnung bekommt ein Ergebnis:
  - **grün** – gültige E-Rechnung
  - **gelb** – gültig mit Hinweisen
  - **rot** – fehlerhaft. Jede Regel ist auf Deutsch erklärt. Mit **Korrektur anfordern** erhalten Sie einen fertigen E-Mail-Text an den Lieferanten.

## 5. Rechnungen ansehen und bearbeiten

Ein Klick auf eine Rechnung zeigt:
- **jedes Feld** mit verständlicher Bezeichnung (Verkäufer, Positionen, Steuer, Zahlungsbedingungen …);
- das **Prüfergebnis** mit dem verwendeten Regelwerk;
- **Überweisung:** IBAN, Betrag, Verwendungszweck mit Kopier-Knöpfen und einem **GiroCode**. Scannen Sie ihn mit Ihrer Banking-App, dann ist die Überweisung ausgefüllt;
- **Downloads:** Original (unverändert, mit Prüfsumme), PDF-Ansichtskopie, eingebettete Anhänge.

**Status:** Neu → Geprüft → Freigegeben → Bezahlt → Gebucht (oder Beanstandet), dazu Schlagwörter und eine Notiz. Jede Änderung steht mit Name und Zeit im Verlauf. Das Original ändert sich nie.

**Kommentare:** für Rückfragen im Team oder an die Steuerberatung. Kommentare lassen sich nicht ändern oder löschen.

**Suche und Filter:** nach Lieferant, Rechnungsnummer, Betrag, Zeitraum, Status, Prüfergebnis.

## 6. Benachrichtigungen

Unter *Einstellungen → Unternehmen → Benachrichtigungen* bestimmt jede Person für sich:
- **neue Dokumente:** eine E-Mail je Upload oder eingehender E-Mail;
- **Fälligkeiten:** eine E-Mail 3 Tage bevor erhaltene Rechnungen fällig sind (voreingestellt an). Bezahlte, gebuchte und beanstandete Rechnungen werden ausgelassen.

## 7. Rechnungen schreiben

### 7.1 Einmal einrichten (Inhaber)
*Einstellungen → Rechnungen*:
- **Kontakt:** Name, Telefon, E-Mail. Für XRechnung sind sie Pflicht.
- **Bank:** IBAN und BIC.
- **Kleinunternehmer (§ 19 UStG):** an oder aus.
- **Nummernformat:** z. B. `RE-{YYYY}-{NNNN}`, getrennt für Gutschriften.
- **Standardformat:** ZUGFeRD (PDF mit Rechnungsdaten, passt für die meisten Geschäftskunden) oder XRechnung (reines XML, üblich für Behörden).
- **Zahlungsziel**
- **Gestaltung:** Logo (PNG oder JPEG, höchstens 512 KB) und Akzentfarbe für ZUGFeRD-Rechnungen.

### 7.2 Kunden und Artikel
Unter *Rechnungen → Kunden* bzw. *Artikel* speichern Sie wiederkehrende Angaben.

### 7.3 Eine Rechnung erstellen
1. *Rechnungen → Neue Rechnung*.
2. Kunde, Leistungsdatum oder -zeitraum und Positionen eintragen. Der Entwurf speichert sich selbst.
3. ERIKA zeigt, was noch fehlt (Pflichtangaben nach § 14 UStG). Erst wenn nichts fehlt, geht **Ausstellen**.
4. Beim Ausstellen vergibt ERIKA die Nummer, erzeugt die Datei, prüft sie mit den amtlichen Regeln und archiviert sie. Findet die Prüfung einen Fehler, wird nichts ausgestellt und die Nummer nicht verbraucht.
5. **Versenden** per E-Mail an den Kunden. Die gesendete E-Mail wird archiviert, die Zustellung protokolliert. Sie können die Datei auch herunterladen und selbst weitergeben.

### 7.4 Korrigieren
Ausgestellte Rechnungen lassen sich **nicht ändern** (GoBD). Stattdessen gibt es **Gutschrift**, **Stornorechnung** oder **korrigierte Rechnung**. Sie verweisen auf das Original, das danach „Storniert“ heißt.

### 7.5 Wiederkehrende Rechnungen
Auf einer Rechnung: **Als wiederkehrende Rechnung anlegen**. Wählen Sie Bezeichnung, Rhythmus (monatlich oder vierteljährlich) und den ersten Termin. An jedem Termin legt ERIKA einen **Entwurf** mit dem passenden Leistungszeitraum an und schickt Ihnen eine E-Mail. Sie prüfen den Entwurf und stellen ihn aus. Automatisch ausgestellt wird nichts. Unter *Rechnungen → Wiederkehrende Rechnungen* können Sie pausieren, fortsetzen oder löschen.

## 8. Team (Inhaber)

*Einstellungen → Team → Einladen*: E-Mail-Adresse und Rolle angeben. Der Link gilt 7 Tage und nur für diese Adresse. Rollenänderungen und Entfernungen wirken sofort.

## 9. Export

*Export*:
- **Zeitraum-Export** für die Steuerberatung: Originale, Übersicht (`index.csv`) und DATEV-Buchungsstapel (Berater- und Mandantennummer unter *Einstellungen → DATEV*).
- **Vollständiger Kontoexport** (nur Inhaber): alles – Originale, Daten, Protokoll mit Prüfsummen. Geeignet für eine Betriebsprüfung (Datenträgerüberlassung).

Exporte werden im Hintergrund erstellt und nach 7 Tagen gelöscht.

## 10. Konto und Sicherheit

- **Passwort ändern:** *Einstellungen → Konto*.
- **Passwort vergessen:** *Anmelden → Passwort vergessen*. Der Link gilt eine Stunde. Der zweite Faktor bleibt nötig.
- **Zu viele Fehlversuche:** Die Anmeldung ist 15 Minuten gesperrt.
- **Abonnement:** Nach der Testphase (14 Tage) ist ein Abonnement nötig. Ohne Abonnement wird der Arbeitsbereich **schreibgeschützt**: Lesen, Herunterladen und Exportieren gehen immer, die Aufbewahrung läuft weiter. Während der Beta ist die Abrechnung ausgeschaltet.
- **Konto schließen** (Inhaber, *Einstellungen → Unternehmen*): Der Arbeitsbereich wird schreibgeschützt, E-Mails an die Eingangsadresse werden abgewiesen. Die Daten bleiben bis zum Ende der Aufbewahrungsfrist erhalten. Das lässt sich nicht rückgängig machen. Machen Sie vorher einen vollständigen Export.

## 11. Häufige Fragen

**Die Rechnung ist rot – muss ich sie zurückweisen?** Eine fehlerhafte E-Rechnung erfüllt die Vorgaben nicht. Fordern Sie eine korrigierte Rechnung an (Knopf **Korrektur anfordern**). Die Entscheidung liegt bei Ihnen und Ihrer Steuerberatung.

**Ist die PDF-Ansichtskopie ein Beleg?** Nein. Das Original ist die E-Rechnung (XML bzw. ZUGFeRD-PDF). Die Ansichtskopie ist nur zum Lesen gedacht und so gekennzeichnet.

**Kann ich ein Dokument löschen?** Nein. Archivierte Originale sind bis zum Ende der Aufbewahrungsfrist unveränderbar. Markieren Sie Irrläufer mit einem Status oder einer Notiz.

**Ich habe mein Telefon verloren.** Melden Sie sich mit einem Wiederherstellungscode an und richten Sie die Authenticator-App neu ein.

**Feedback:** Knopf **Feedback** oben rechts – er geht direkt an das ERIKA-Team.
