package de.erika.reports

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone
import javax.imageio.ImageIO

/** Everything the archive PDF of a signed report shows. */
internal data class ReportPdfContent(
    val firmName: String,
    val firmAddress: String?,
    /** The canonical report input (what was hashed and signed). */
    val report: Map<String, Any?>,
    val contentHash: String,
    val signature: ReportSignature,
    val verificationUrl: String,
    /** Photo ID → thumbnail JPEG, for the photos whose file arrived. */
    val photoImages: Map<String, ByteArray>,
)

/**
 * The master PDF of a signed (or unsigned-sent) report version, rendered by the worker from the
 * server's data and archived write-once (ADR 0040). Times are shown in German local time; the
 * content is the canonical report, so it matches the hash printed at the bottom of every page.
 */
internal object ReportPdf {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private val zone = ZoneId.of("Europe/Berlin")
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private const val MARGIN = 50f
    private const val SIZE = 10f
    private const val LEADING = 14f

    fun render(c: ReportPdfContent): ByteArray =
        PDDocument().use { doc ->
            doc.documentInformation.apply {
                title = "Arbeitsbericht ${text(c.report, "workOrder", "title")}"
                creator = "ERIKA / MARA"
                creationDate =
                    Calendar
                        .getInstance(
                            TimeZone.getTimeZone("UTC"),
                        ).apply {
                            timeInMillis =
                                c.signature.signedAt
                                    .toInstant()
                                    .toEpochMilli()
                        }
            }
            Writer(doc, c).apply {
                body()
                close()
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private fun text(
        map: Map<String, Any?>,
        vararg path: String,
    ): String {
        var v: Any? = map
        for (p in path) v = (v as? Map<*, *>)?.get(p)
        return (v as? String)?.trim() ?: ""
    }

    private fun local(iso: String?): String = iso?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(dateTime) } ?: ""

    private fun decimal(v: Any?): String =
        when (v) {
            null -> ""
            is java.math.BigDecimal -> v.stripTrailingZeros().toPlainString().replace('.', ',')
            else -> v.toString().replace('.', ',')
        }

    private class Writer(
        val doc: PDDocument,
        val c: ReportPdfContent,
    ) {
        private var page = PDPage(PDRectangle.A4)
        private var out: PDPageContentStream
        private var y = 0f
        private var pageNumber = 0
        private val width = PDRectangle.A4.width - 2 * MARGIN

        init {
            out = newPage()
        }

        private fun newPage(): PDPageContentStream {
            page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            pageNumber++
            val stream = PDPageContentStream(doc, page)
            y = PDRectangle.A4.height - MARGIN
            footer(stream)
            return stream
        }

        /** Every page names the version, the hash and where to check it. */
        private fun footer(stream: PDPageContentStream) {
            val lines =
                listOf(
                    "SHA-256: ${c.contentHash}",
                    "Prüfcode ${c.signature.verificationCode} – ${c.verificationUrl} – Seite $pageNumber",
                )
            lines.forEachIndexed { i, line ->
                stream.beginText()
                stream.setFont(regular, 7f)
                stream.newLineAtOffset(MARGIN, 30f - i * 9f)
                stream.showText(safe(line, regular))
                stream.endText()
            }
        }

        private fun ensure(height: Float) {
            if (y - height < 60f) {
                out.close()
                out = newPage()
            }
        }

        fun line(
            s: String,
            font: PDType1Font = regular,
            size: Float = SIZE,
            indent: Float = 0f,
        ) {
            for (part in wrap(safe(s, font), font, size, width - indent)) {
                ensure(LEADING)
                out.beginText()
                out.setFont(font, size)
                out.newLineAtOffset(MARGIN + indent, y - size)
                out.showText(part)
                out.endText()
                y -= if (size > SIZE) size + 6f else LEADING
            }
        }

        private fun heading(s: String) {
            y -= 6f
            ensure(40f)
            line(s, bold, 12f)
        }

        private fun gap() {
            y -= 6f
        }

        @Suppress("UNCHECKED_CAST")
        private fun list(key: String): List<Map<String, Any?>> = (c.report[key] as? List<Map<String, Any?>>).orEmpty()

        fun body() {
            val r = c.report
            line(c.firmName, bold, 11f)
            c.firmAddress?.let { line(it) }
            gap()
            line("Arbeitsbericht", bold, 16f)
            line("Auftrag: ${text(r, "workOrder", "title")}")
            line("Kunde: ${text(r, "customer", "name")}")
            line("Version ${r["version"]}")

            val times = list("times").sortedBy { it["id"] as String }
            if (times.isNotEmpty()) {
                heading("Zeiten")
                for (t in times) {
                    val kind = if (t["kind"] == "TRAVEL") "Fahrt" else "Arbeit"
                    val end = (t["end"] as String?)?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(time) } ?: "läuft"
                    line("${local(t["start"] as String?)} – $end  $kind  ${t["technician"] ?: ""}")
                    (t["note"] as String?)?.takeIf { it.isNotBlank() }?.let { line(it, indent = 12f) }
                }
            }
            val materials = list("materials").sortedBy { it["id"] as String }
            if (materials.isNotEmpty()) {
                heading("Material")
                materials.forEach { line("${decimal(it["quantity"])} ${unit(it["unit"] as String?)}  ${it["description"]}") }
            }
            val aufmass = list("aufmass").sortedBy { it["id"] as String }
            if (aufmass.isNotEmpty()) {
                heading("Aufmaß")
                for (p in aufmass) {
                    line("${p["label"]}: ${decimal(p["result"])} ${unit(p["unit"] as String?)}", bold)
                    val dims = listOf("length", "width", "height").mapNotNull { p[it] }.joinToString(" × ") { decimal(it) }
                    val detail = if (p["kind"] == "FORMULA") (p["expression"] as String? ?: "") else dims

                    @Suppress("UNCHECKED_CAST")
                    val deductions = (p["deductions"] as? List<Map<String, Any?>>).orEmpty()
                    val minus =
                        deductions.joinToString(", ") { d ->
                            listOfNotNull(
                                d["label"] as String?,
                                listOf("length", "width", "height").mapNotNull { d[it] }.joinToString(" × ") { decimal(it) },
                            ).joinToString(" ")
                        }
                    line(detail + (if (minus.isNotEmpty()) "  abzüglich $minus" else ""), indent = 12f)
                }
            }
            val checklist = list("checklist").sortedBy { it["key"] as String }
            if (checklist.isNotEmpty()) {
                heading("Checkliste")
                checklist.forEach {
                    val value =
                        when (it["value"]) {
                            "YES" -> "Ja"
                            "NO" -> "Nein"
                            null -> "–"
                            else -> it["value"].toString()
                        }
                    line("${it["label"]}: $value")
                }
            }
            (r["workDescription"] as String?)?.takeIf { it.isNotBlank() }?.let {
                heading("Arbeitsbeschreibung")
                it.lines().forEach { l -> line(l) }
            }
            val photos = list("photos").sortedBy { it["id"] as String }
            if (photos.isNotEmpty()) {
                heading("Fotos")
                photos.forEach { photo(it) }
            }
            signature()
        }

        private fun photo(p: Map<String, Any?>) {
            val label =
                listOfNotNull(
                    mapOf("BEFORE" to "Vorher", "AFTER" to "Nachher", "DEFECT" to "Mangel").getOrDefault(p["category"], "Foto"),
                    p["caption"] as String?,
                ).joinToString(": ")
            val bytes = c.photoImages[p["id"] as String]
            if (bytes == null) {
                line("$label (Datei nicht übertragen, SHA-256 ${p["sha256"]})")
                return
            }
            val image = JPEGFactory.createFromByteArray(doc, bytes)
            val scale = minOf(220f / image.width, 165f / image.height)
            val w = image.width * scale
            val h = image.height * scale
            ensure(h + LEADING + 6f)
            out.drawImage(image, MARGIN, y - h, w, h)
            y -= h + 4f
            line(label)
            gap()
        }

        private fun signature() {
            val s = c.signature
            heading(if (s.kind == "SIGNED") "Abnahme" else "Ohne Unterschrift")
            if (s.kind == "SIGNED") {
                s.signaturePng?.let { png ->
                    val image: PDImageXObject = LosslessFactory.createFromImage(doc, ImageIO.read(ByteArrayInputStream(png)))
                    val scale = minOf(220f / image.width, 90f / image.height)
                    ensure(image.height * scale + 50f)
                    out.drawImage(image, MARGIN, y - image.height * scale, image.width * scale, image.height * scale)
                    y -= image.height * scale + 4f
                }
                line("${s.signerName}, ${local(s.signedAt.toInstant().toString())}")
                s.comment?.let { line("Anmerkung: $it") }
            } else {
                line("Nicht unterschrieben am ${local(s.signedAt.toInstant().toString())}. Grund: ${s.notSignedReason}")
            }
            gap()
            line("Der Inhalt dieses Berichts ist durch den SHA-256-Wert unten gesichert.", size = 8f)
        }

        fun close() = out.close()

        private fun unit(code: String?): String =
            mapOf(
                "C62" to "Stück",
                "MTR" to "m",
                "MTK" to "m²",
                "MTQ" to "m³",
                "KGM" to "kg",
                "LTR" to "l",
                "HUR" to "Std.",
                "LS" to "pauschal",
            )[code]
                ?: (code ?: "")
    }

    /** Characters the standard font can't show (outside WinAnsi) become "?". */
    private fun safe(
        s: String,
        font: PDType1Font,
    ): String =
        buildString {
            for (ch in s.replace('\t', ' ')) {
                val ok = ch >= ' ' && runCatching { font.encode(ch.toString()) }.isSuccess
                append(if (ok) ch else '?')
            }
        }

    private fun wrap(
        s: String,
        font: PDType1Font,
        size: Float,
        max: Float,
    ): List<String> {
        val words = s.split(' ')
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (font.getStringWidth(candidate) / 1000 * size <= max || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        lines += current
        return lines
    }
}
