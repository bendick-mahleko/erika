# Verzeichnis von Verarbeitungstätigkeiten (Art. 30 DSGVO) – Entwurf

Stand: 23.11.2026 · Entwurf des Entwicklungsteams, keine Rechtsberatung. Platzhalter in [eckigen Klammern] füllt der Betreiber aus; Prüfung durch eine Anwältin oder einen Anwalt in Stage 11 (NF-09).

**Verantwortlicher:** [Name, Anschrift, Kontakt des Betreibers] · **Datenschutzbeauftragte/r:** [falls benannt, sonst „nicht erforderlich“, mit Begründung]

## Teil A – ERIKA als Verantwortlicher (Art. 30 Abs. 1)

| Nr. | Verarbeitung | Zweck | Betroffene | Datenkategorien | Empfänger | Löschfrist | Rechtsgrundlage |
|---|---|---|---|---|---|---|---|
| A1 | Benutzerkonten und Anmeldung | Bereitstellung des Dienstes, sichere Anmeldung mit zweitem Faktor | Benutzer/innen | Name, E-Mail, Passwort-Hash (Argon2id), TOTP-Geheimnis (verschlüsselt), Wiederherstellungscodes (Hash), Sprache, akzeptierte AGB-Version | Hetzner (Hosting), E-Mail-Dienstleister (Systemmails) | Mit Löschung des Kontos (Anonymisierung, docs/ops/gdpr.md) | Art. 6 Abs. 1 lit. b |
| A2 | Sicherheitsprotokolle | Schutz vor Missbrauch: Sperre nach Fehlversuchen, Hinweis auf neue Geräte | Benutzer/innen | Fehlversuche, Sperrzeit, Geräte-Hash, Zeitpunkte | Hetzner | Geräte: mit dem Konto; Server-Logs 30 Tage | Art. 6 Abs. 1 lit. f |
| A3 | Änderungsprotokoll (Audit) | Nachvollziehbarkeit nach GoBD | Benutzer/innen | Benutzer-ID, Aktion, Zeitpunkt (keine Rechnungsinhalte) | Hetzner | Bis zur Löschung des Arbeitsbereichs | Art. 6 Abs. 1 lit. c i. V. m. § 147 AO; lit. f |
| A4 | Einladungen in einen Arbeitsbereich | Zusammenarbeit im Team und mit der Steuerberatung | Eingeladene | E-Mail-Adresse, Rolle, Zeitpunkte | E-Mail-Dienstleister | Mit Löschung des Arbeitsbereichs | Art. 6 Abs. 1 lit. b, f |
| A5 | Feedback in der Beta | Verbesserung des Dienstes | Benutzer/innen | Freitext, Seite, Zeitpunkt | ERIKA-Team | 2 Jahre | Art. 6 Abs. 1 lit. f |
| A6 | Abrechnung (ab Stage 10) | Vertragsabwicklung | Inhaber/innen | Rechnungsadresse, Zahlungsdaten (bei Stripe) | Stripe Payments Europe | 10 Jahre (§ 147 AO) | Art. 6 Abs. 1 lit. b, c |

## Teil B – ERIKA als Auftragsverarbeiter (Art. 30 Abs. 2)

**Auftraggeber:** die Kundinnen und Kunden mit eigenem Arbeitsbereich (Vertrag zur Auftragsverarbeitung, `/auftragsverarbeitung`).

| Nr. | Kategorie der Verarbeitung | Daten | Unterauftragsverarbeiter | Übermittlung in Drittländer |
|---|---|---|---|---|
| B1 | Empfang, Prüfung, Anzeige und Archivierung von Eingangsrechnungen | Rechnungsdaten mit Angaben zu Geschäftspartnern (Namen, Anschriften, Kontakte, Bankverbindungen), E-Mails an die Eingangsadresse | Hetzner | Nein |
| B2 | Erstellung und Versand von Ausgangsrechnungen | Kunden- und Artikelstammdaten, Rechnungen, gesendete E-Mails | Hetzner, E-Mail-Dienstleister | Nein |
| B3 | Kommentare und Status | Freitexte der Benutzer/innen zu Dokumenten | Hetzner | Nein |
| B4 | Exporte (Zeitraum mit DATEV-Datei, vollständiger Kontoexport) | Wie B1–B3; ZIP-Dateien werden nach 7 Tagen gelöscht | Hetzner | Nein |

## Technische und organisatorische Maßnahmen (Art. 32)

Siehe Anlage TOM im Vertrag zur Auftragsverarbeitung (`/auftragsverarbeitung`). Kurzfassung: Hosting in Deutschland; Mandantentrennung in der Datenbank (Row-Level Security) mit automatisierten Tests; Anmeldung nur mit zweitem Faktor; Steuerberater/innen nur lesend; Originale unveränderbar (Object Lock); hash-verkettetes Änderungsprotokoll; verschlüsselte Backups an einem zweiten Standort; Verschlüsselung bei der Übertragung (TLS); Exporte nur nach Anmeldung und mit Protokoll.

## Pflege

Bei jeder neuen Verarbeitung, jedem neuen Unterauftragsverarbeiter oder geänderten Löschfristen aktualisieren. Verweis in docs/legal/README.md.
