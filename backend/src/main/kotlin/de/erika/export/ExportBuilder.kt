package de.erika.export

import de.erika.archive.Archive
import de.erika.documents.DocumentExports
import de.erika.documents.ExportDocument
import de.erika.platform.export.AccountDataSection
import org.springframework.stereotype.Component
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

/** A ZIP built in a temporary file; the caller uploads and deletes it. */
internal class BuiltExport(
    val file: Path,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val documentCount: Int,
)

/**
 * Builds export ZIPs in the worker, in the export's workspace transaction:
 * - PERIOD (EX-01): `Belege/Eingang|Ausgang/…` with the originals (hash-checked by the archive),
 *   `index.csv`, `DATEV/EXTF_Buchungsstapel_….csv` per fiscal year (when consultant and client
 *   number are set) and `LIESMICH.txt`;
 * - FULL (EX-04, NF-09): `archiv/<sha256>.<ext>` with every archived original, `archiv.json`,
 *   `daten/<section>.json` from every module's [AccountDataSection], `LIESMICH.txt` and `MANIFEST.sha256`
 *   (SHA-256 of every other file, `sha256sum -c` format).
 */
@Component
internal class ExportBuilder(
    private val archive: Archive,
    private val documents: DocumentExports,
    private val datev: DatevSettingsRepository,
    private val sections: List<AccountDataSection>,
    json: JsonMapper,
) {
    private val pretty: JsonMapper = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build()

    init {
        val names = sections.map { it.fileName }
        check(names.size == names.toSet().size) { "Duplicate account data section file names: $names" }
    }

    fun build(export: ExportRow): BuiltExport =
        when (export.kind) {
            ExportKind.PERIOD -> period(export)
            ExportKind.FULL -> full(export)
        }

    private fun period(export: ExportRow): BuiltExport {
        val from = checkNotNull(export.from)
        val to = checkNotNull(export.to)
        val direction = export.scope.takeUnless { it == ExportScope.ALL }?.name
        val docs = documents.forPeriod(from, to, direction)
        val config = datev.current().config
        val now = LocalDateTime.now(BERLIN)
        val fileName = "ERIKA-Export_${from}_bis_$to.zip"
        return write(fileName, docs.size) { zip ->
            val names = ZipNames()
            val files = mutableMapOf<UUID, String>()
            docs.forEach { doc ->
                val content = archive.open(export.workspaceId, doc.sha256) ?: error("Original ${doc.sha256.take(12)} missing")
                val folder = if (doc.direction == "IN") "Belege/Eingang" else "Belege/Ausgang"
                val ext = ZipNames.extensionFor(doc.mediaType)
                val name = names.unique(folder, ZipNames.forDocument(doc.bookingDate, doc.invoiceNumber, doc.fileName, ext))
                zip.add(name, content.bytes)
                files[doc.id] = name
            }
            zip.add("index.csv", IndexCsv.write(docs.map { it to files.getValue(it.id) }))
            val datevFiles = mutableListOf<String>()
            if (config.complete) {
                DatevCsv.fiscalYears(from, to, config.fiscalYearStartMonth).forEach { (fyStart, fyFrom, fyTo) ->
                    val inYear = docs.filter { !it.bookingDate.isBefore(fyFrom) && !it.bookingDate.isAfter(fyTo) }
                    val bookings = DatevCsv.bookings(inYear, config) { files[it.id] }
                    val name = "DATEV/EXTF_Buchungsstapel_${fyFrom.format(COMPACT)}_${fyTo.format(COMPACT)}.csv"
                    zip.add(name, DatevCsv.write(config, fyStart, fyFrom, fyTo, bookings, now, "ERIKA $fyFrom bis $fyTo"))
                    datevFiles += name
                }
            }
            zip.add("LIESMICH.txt", periodReadme(export, docs, config, datevFiles, now).toByteArray(Charsets.UTF_8))
        }
    }

    private fun full(export: ExportRow): BuiltExport {
        val originals = archive.all(export.workspaceId)
        val now = LocalDateTime.now(BERLIN)
        val fileName = "ERIKA-Kontoexport_${now.toLocalDate()}.zip"
        return write(fileName, originals.size) { zip ->
            val index =
                originals.map { stored ->
                    val content = archive.open(export.workspaceId, stored.sha256) ?: error("Original ${stored.sha256.take(12)} missing")
                    val name = "archiv/${stored.sha256}${ZipNames.extensionFor(stored.mediaType)}"
                    zip.add(name, content.bytes)
                    linkedMapOf(
                        "file" to name,
                        "sha256" to stored.sha256,
                        "sizeBytes" to stored.sizeBytes,
                        "mediaType" to stored.mediaType,
                        "storedAt" to stored.storedAt.toString(),
                        "retentionUntil" to stored.retentionUntil.toString(),
                        "objectLock" to stored.objectLock.name,
                        "legalHold" to stored.legalHold,
                    )
                }
            zip.add("archiv.json", pretty.writeValueAsBytes(index))
            sections.sortedBy { it.fileName }.forEach { section ->
                zip.add("daten/${section.fileName}", pretty.writeValueAsBytes(section.collect(export.workspaceId)))
            }
            zip.add("LIESMICH.txt", fullReadme(originals.size, now).toByteArray(Charsets.UTF_8))
            val manifest = zip.hashes.entries.joinToString("") { (name, sha) -> "$sha  $name\n" }
            zip.add("MANIFEST.sha256", manifest.toByteArray(Charsets.UTF_8))
        }
    }

    private fun write(
        fileName: String,
        documentCount: Int,
        content: (HashingZip) -> Unit,
    ): BuiltExport {
        val file = Files.createTempFile("erika-export-", ".zip")
        try {
            Files.newOutputStream(file).use { out -> HashingZip(out).use(content) }
            return BuiltExport(file, fileName, sha256(file), Files.size(file), documentCount)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Files.deleteIfExists(file)
            throw e
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun periodReadme(
        export: ExportRow,
        docs: List<ExportDocument>,
        config: DatevConfig,
        datevFiles: List<String>,
        now: LocalDateTime,
    ): String =
        buildString {
            appendLine("ERIKA – Export für den Zeitraum ${export.from} bis ${export.to}")
            appendLine("Erstellt am ${now.format(READABLE)} (Europe/Berlin)")
            appendLine()
            appendLine(
                "Umfang: " +
                    mapOf(
                        ExportScope.IN to "Eingangsrechnungen",
                        ExportScope.OUT to "Ausgangsrechnungen",
                        ExportScope.ALL to "Eingangs- und Ausgangsrechnungen",
                    ).getValue(export.scope),
            )
            appendLine("Dokumente: ${docs.size}")
            appendLine()
            appendLine("Inhalt")
            appendLine("- Belege/Eingang, Belege/Ausgang: die Originaldateien, unverändert aus dem Archiv (SHA-256 geprüft).")
            appendLine("- index.csv: ein Eintrag je Dokument mit Dateiname, SHA-256 und Rechnungsdaten (Semikolon, UTF-8).")
            if (datevFiles.isNotEmpty()) {
                appendLine("- ${datevFiles.joinToString(", ")}: DATEV-Buchungsstapel (EXTF, Version 13, ${config.chart.name}),")
                appendLine("  Beraternummer ${config.consultantNumber}, Mandantennummer ${config.clientNumber}.")
                appendLine(
                    "  Personenkonten: Kreditor ${config.creditor}, Debitor ${config.debtor}; Sachkonten: Aufwand ${config.expense},",
                )
                appendLine("  Erlöse ${config.revenue} (ohne Automatikfunktion). Steuerschlüssel nur für 19 % und 7 %; andere Fälle")
                appendLine("  (Reverse Charge, innergemeinschaftliche Lieferung, steuerfrei) bitte in der Kanzlei kontieren.")
                appendLine("  Der Stapel ist nicht festgeschrieben.")
            } else {
                appendLine("- Kein DATEV-Buchungsstapel: In ERIKA fehlen Berater- und Mandantennummer (Einstellungen → DATEV).")
            }
            appendLine()
            appendLine("Zeitraum: maßgeblich ist das Rechnungsdatum, bei Dokumenten ohne Rechnungsdaten das Eingangsdatum.")
            appendLine("Dokumente ohne Beträge (z. B. reine PDF) stehen im Index, aber nicht im Buchungsstapel.")
        }

    private fun fullReadme(
        originals: Int,
        now: LocalDateTime,
    ): String =
        buildString {
            appendLine("ERIKA – vollständiger Kontoexport")
            appendLine("Erstellt am ${now.format(READABLE)} (Europe/Berlin)")
            appendLine()
            appendLine("- archiv/: alle $originals archivierten Originale (Rechnungen, empfangene und gesendete E-Mails),")
            appendLine("  benannt nach ihrem SHA-256; archiv.json nennt Medientyp, Aufbewahrungsfrist und Sperre.")
            appendLine("- daten/arbeitsbereich.json: Stammdaten, Mitglieder, Einladungen.")
            appendLine("- daten/dokumente.json: alle Dokumente mit Rechnungsdaten, Prüfergebnissen, Statusverlauf und Kommentaren.")
            appendLine("- daten/rechnungserstellung.json: Einstellungen, Kunden, Artikel, Entwürfe, Rechnungen, Versand.")
            appendLine("- daten/protokoll.json: das Änderungsprotokoll mit Hash-Kette (prüfbar, siehe ERIKA-Dokumentation).")
            appendLine("- MANIFEST.sha256: SHA-256 jeder Datei; prüfen mit `sha256sum -c MANIFEST.sha256`.")
        }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        val COMPACT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val READABLE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        const val BUFFER = 64 * 1024
    }
}
