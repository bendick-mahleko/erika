# ERIKA – Handbuch für Steuerberaterinnen und Steuerberater

Ihre Mandantinnen und Mandanten empfangen, prüfen und schreiben E-Rechnungen mit ERIKA. Dieses Handbuch zeigt, wie Sie als Kanzlei darauf zugreifen, was Sie dort sehen und was Sie exportieren können. Die ausführliche Verfahrensdokumentation (GoBD) finden Sie in [docs/legal/verfahrensdokumentation.md](../legal/verfahrensdokumentation.md).

---

## 1. Ihre Rolle: Steuerberater

Sie werden vom Inhaber des Arbeitsbereichs eingeladen und haben die Rolle **Steuerberater**:

| Sie können | Sie können nicht |
|---|---|
| alle Dokumente, Prüfergebnisse, Verläufe und Ausgangsrechnungen lesen | Status, Notizen oder Einstellungen ändern |
| Originale, Ansichtskopien und Anhänge herunterladen | Rechnungen schreiben, ausstellen oder versenden |
| Dokumente **kommentieren** | Dokumente löschen (das kann niemand) |
| **Exporte** anfordern und herunterladen | Team, Absenderliste oder Abonnement verwalten |
| Feedback an das ERIKA-Team senden | |

Die Rechte werden auf dem Server geprüft, nicht nur in der Oberfläche: Jeder Versuch, etwas zu ändern, wird abgewiesen.

## 2. Zugang einrichten

1. Der Mandant lädt Sie unter *Einstellungen → Team* mit Ihrer E-Mail-Adresse und der Rolle „Steuerberater“ ein.
2. Sie erhalten eine E-Mail. Der Link gilt **7 Tage** und nur für diese Adresse.
   - **Neu bei ERIKA:** Konto anlegen, E-Mail bestätigen, Authenticator-App einrichten. Ein eigener Arbeitsbereich entsteht dabei nicht.
   - **Schon ein ERIKA-Konto (z. B. für einen anderen Mandanten):** anmelden und die Einladung annehmen.
3. Mehrere Mandanten erscheinen oben in der Kopfzeile, dort wechseln Sie zwischen ihnen. Pro Sitzung sehen Sie immer nur einen Arbeitsbereich.

Jede Anmeldung braucht Passwort und Code aus der Authenticator-App. Kanzleien mit mehreren Personen: Jede Person braucht ein eigenes Konto. Teilen Sie keine Zugangsdaten.

## 3. Was Sie sehen

- **Eingang:** alle empfangenen Rechnungen mit Format (XRechnung UBL/CII, ZUGFeRD/Factur-X, sonstige PDF), Prüfergebnis (grün/gelb/rot) und Bearbeitungsstatus (Neu, Geprüft, Freigegeben, Bezahlt, Beanstandet, Gebucht).
- **Dokumentansicht:**
  - jedes Geschäftsfeld (BT/BG nach EN 16931) mit deutscher Bezeichnung;
  - das Prüfergebnis je Regel mit dem angewandten Regelwerk (Version);
  - der Verlauf aller Statusänderungen mit Person und Zeit.
- **Rechnungen:** Ausgangsrechnungen, Gutschriften, Stornorechnungen mit Nummer, Status und Zustellprotokoll.
- **Englisch:** Mit **EN** in der Kopfzeile erscheinen Oberfläche und Rechnungsansicht auf Englisch, etwa für ausländische Lieferanten.

**Kommentare** nutzen Sie für Rückfragen zu einzelnen Belegen („Bitte Leistungszeitraum nachreichen“). Der Mandant sieht sie sofort. Kommentare können nicht geändert oder gelöscht werden; sie sind Teil der Dokumentation.

## 4. Export

*Export → Neuer Export*:

### Zeitraum-Export
Eine ZIP-Datei mit:
- allen **Originalen** des Zeitraums. Maßgeblich ist das Rechnungsdatum, sonst das Eingangsdatum;
- `index.csv`: Dateiname, SHA-256-Prüfsumme und Rechnungsdaten je Beleg;
- einem **DATEV-Buchungsstapel** (Format EXTF, nicht festgeschrieben) je Wirtschaftsjahr, wenn der Mandant unter *Einstellungen → DATEV* Berater- und Mandantennummer eingetragen hat.

**Wichtig zum Buchungsstapel:** Er ist ein **Vorschlag**. Er enthält eine Buchung je Rechnung und Steuersatz gegen Kreditor bzw. Debitor und ein Aufwands- bzw. Erlöskonto, ohne Automatikfunktion. Steuerschlüssel gibt es nur für 19 % und 7 %. Bitte prüfen und kontieren Sie vor dem Festschreiben. Fälle wie § 13b UStG oder innergemeinschaftliche Lieferungen sind im Beleg korrekt ausgewiesen, im Stapel aber ohne Steuerschlüssel.

### Vollständiger Kontoexport
Kann nur der Inhaber anfordern. Er enthält alle Originale, alle Daten als JSON, das Audit-Protokoll mit Hash-Kette und eine Prüfsummenliste (`MANIFEST.sha256`). Er eignet sich für den Datenzugriff der Finanzverwaltung (Z3, Datenträgerüberlassung).

Exporte werden im Hintergrund erstellt, sind nur nach Anmeldung abrufbar und werden nach **7 Tagen** gelöscht. Anforderung und Download stehen im Audit-Protokoll des Mandanten.

## 5. Ordnungsmäßigkeit auf einen Blick

| Anforderung | Umsetzung in ERIKA |
|---|---|
| Unveränderbarkeit (GoBD Rz. 58 ff.) | Originale write-once im Objektspeicher (Object Lock, Compliance-Modus); Prüfsumme bei jedem Abruf geprüft |
| Aufbewahrung (§ 147 AO, § 14b UStG) | 8 Jahre ab Ende des Kalenderjahres, für Belege vor 2025 10 Jahre; Frist je Dokument gespeichert |
| Nachvollziehbarkeit | Audit-Protokoll mit Hash-Kette für jede Änderung, jeden Export, jede Rollenänderung |
| Belegprüfung | amtliche KoSIT-Prüfkonfiguration und EN-16931-Regeln; das Regelwerk (Version) wird mit dem Ergebnis gespeichert; „Erneut prüfen“ behält frühere Ergebnisse |
| Ausgangsrechnungen | lückenlose Nummern; Korrektur nur durch Gutschrift/Storno; Pflichtangaben nach § 14 Abs. 4 UStG geprüft |
| Datenzugriff (§ 147 Abs. 6 AO) | vollständiger Kontoexport mit Prüfsummen |
| Rechenzentrum | Hetzner, Nürnberg und Falkenstein (Deutschland) |

Die Verfahrensdokumentation wird vor dem Start von einer Steuerberatung geprüft ([docs/legal/tax-advisor-review.md](../legal/tax-advisor-review.md)). Hinweise dazu nehmen wir gern über den Feedback-Knopf entgegen.

## 6. Häufige Fragen

**Ist eine rote (fehlerhafte) E-Rechnung zum Vorsteuerabzug geeignet?** Das ist Ihre fachliche Beurteilung. ERIKA zeigt die verletzten Regeln verständlich an und bietet dem Mandanten an, eine Korrektur beim Lieferanten anzufordern.

**Woran erkenne ich das Original?** Im Download-Bereich steht „Original herunterladen“, mit SHA-256-Prüfsumme. Die PDF-Ansichtskopie trägt auf jeder Seite den Vermerk „Ansichtskopie – Original ist die XML-Datei“.

**Kann ich Belege selbst als gebucht markieren?** Nein, Statusänderungen nimmt der Mandant vor. Nutzen Sie Kommentare, oder bitten Sie den Mandanten, Ihnen die Mitarbeiterrolle zu geben, wenn Sie Belege für ihn bearbeiten.

**Gibt es eine direkte DATEV-Schnittstelle?** Noch nicht. Sie ist für ein späteres Release geplant und setzt die Teilnahme am DATEV-Partnerprogramm voraus. Bis dahin nutzen Sie den Buchungsstapel im Zeitraum-Export.
