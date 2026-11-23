package de.erika.export

import de.erika.documents.ExportDocument
import java.io.OutputStream
import java.math.BigDecimal
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The index of a period export (EX-01): one line per document with its file in the ZIP, the
 * SHA-256 of the original and the invoice data. Semicolon-separated UTF-8 with BOM and CRLF, so
 * spreadsheet programs in Germany open it directly; amounts with decimal comma.
 */
object IndexCsv {
    val HEADER =
        listOf(
            "Datei",
            "SHA-256",
            "Richtung",
            "Eingang",
            "Rechnungsnummer",
            "Rechnungsdatum",
            "Fällig",
            "Rechnungsart",
            "Verkäufer",
            "USt-IdNr. Verkäufer",
            "Käufer",
            "USt-IdNr. Käufer",
            "Netto",
            "USt",
            "Brutto",
            "Währung",
            "Format",
            "Prüfergebnis",
            "Status",
            "Dokument-ID",
        )

    fun write(rows: List<Pair<ExportDocument, String>>): ByteArray {
        val out = StringBuilder("")
        out.append(HEADER.joinToString(";") { cell(it) }).append("\r\n")
        rows.forEach { (d, file) ->
            val fields =
                listOf(
                    file,
                    d.sha256,
                    if (d.direction == "IN") "Eingang" else "Ausgang",
                    d.receivedAt.toLocalDate().toString(),
                    d.invoiceNumber,
                    d.issueDate?.toString(),
                    d.dueDate?.toString(),
                    typeName(d.typeCode),
                    d.sellerName,
                    d.sellerVatId,
                    d.buyerName,
                    d.buyerVatId,
                    d.net.money(),
                    d.tax.money(),
                    d.gross.money(),
                    d.currency,
                    d.format,
                    d.validationLevel,
                    d.status,
                    d.id.toString(),
                )
            out.append(fields.joinToString(";") { cell(it.orEmpty()) }).append("\r\n")
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun typeName(code: String?) =
        when (code) {
            null -> null
            "380" -> "Rechnung (380)"
            "381" -> "Gutschrift (381)"
            "384" -> "Korrigierte Rechnung (384)"
            "389" -> "Selbst ausgestellte Rechnung (389)"
            "326" -> "Teilrechnung (326)"
            "875", "876", "877" -> "Abschlags-/Schlussrechnung ($code)"
            else -> code
        }

    private fun BigDecimal?.money() = this?.toPlainString()?.replace('.', ',')

    /** Every cell quoted; a formula-looking text gets a leading apostrophe (CSV injection). */
    private fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toBigDecimalOrNull() == null) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}

/** File names inside the ZIP: ASCII-safe, unique. */
internal class ZipNames {
    private val used = mutableSetOf<String>()

    fun unique(
        folder: String,
        name: String,
    ): String {
        val clean = safe(name).ifBlank { "datei" }
        val dot = clean.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { clean.substring(0, it) } ?: clean
        val ext = dot?.let { clean.substring(it) } ?: ""
        var candidate = "$folder/$clean"
        var n = 2
        while (!used.add(candidate.lowercase())) candidate = "$folder/${stem}_${n++}$ext"
        return candidate
    }

    companion object {
        fun safe(name: String): String =
            name
                .replace('ä', 'a')
                .replace('ö', 'o')
                .replace('ü', 'u')
                .replace('Ä', 'A')
                .replace('Ö', 'O')
                .replace('Ü', 'U')
                .replace("ß", "ss")
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_', '.')
                .take(120)

        /** `2026-10-05_RE-2026-0001_rechnung.xml` */
        fun forDocument(
            date: LocalDate,
            number: String?,
            fileName: String?,
            fallbackExt: String,
        ): String {
            val parts = listOfNotNull(date.toString(), number?.let(::safe)?.takeIf { it.isNotBlank() }?.take(40))
            val file = fileName?.let(::safe)?.takeIf { it.isNotBlank() } ?: "original$fallbackExt"
            return (parts + file).joinToString("_")
        }

        fun extensionFor(mediaType: String): String =
            when (mediaType.substringBefore(';').trim().lowercase()) {
                "application/pdf" -> ".pdf"
                "application/xml", "text/xml" -> ".xml"
                "message/rfc822" -> ".eml"
                "application/zip" -> ".zip"
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                else -> ".bin"
            }
    }
}

/**
 * Writes ZIP entries and remembers each entry's SHA-256 (for the manifest of the full export).
 */
internal class HashingZip(
    out: OutputStream,
) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    val hashes = linkedMapOf<String, String>()

    fun add(
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(NonClosing(zip), digest).use { it.write(bytes) }
        zip.closeEntry()
        hashes[name] = HexFormat.of().formatHex(digest.digest())
    }

    override fun close() = zip.close()

    private class NonClosing(
        private val target: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = target.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = target.write(b, off, len)

        override fun close() = Unit
    }
}
