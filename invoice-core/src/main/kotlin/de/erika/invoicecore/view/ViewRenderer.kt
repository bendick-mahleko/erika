package de.erika.invoicecore.view

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.text.Normalizer

/**
 * Renders an [InvoiceView] as a PDF "view copy" (VW-02). Every page carries [marking] at the
 * top and bottom, so the copy can never be mistaken for the original (BR-01: the original
 * is the e-invoice file).
 *
 * Uses the PDF standard fonts (no font files to embed). Characters those fonts can't show
 * are replaced: [printable] tells which text ends up on the page, so tests can compare.
 */
public object ViewRenderer {
    public const val DEFAULT_MARKING: String = "Ansichtskopie – Original ist die XML-Datei"

    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private const val MARGIN = 50f
    private const val TOP_BAND = 40f
    private const val BOTTOM_BAND = 40f
    private const val SIZE = 9.5f
    private const val LEADING = 12.5f
    private const val LABEL_SHARE = 0.38f

    public fun pdf(
        view: InvoiceView,
        marking: String = DEFAULT_MARKING,
    ): ByteArray {
        val ops = Layout(PDRectangle.A4).apply { render(view) }.pages
        PDDocument().use { doc ->
            ops.forEachIndexed { index, pageOps ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    pageOps.forEach { it.draw(cs) }
                    val footer = "${printable(marking)} · Seite ${index + 1} von ${ops.size}"
                    drawText(cs, bold, 10f, MARGIN, PDRectangle.A4.height - 28f, printable(marking))
                    drawText(cs, regular, 8f, MARGIN, 22f, footer)
                }
            }
            doc.documentInformation.title = printable(view.title)
            doc.documentInformation.subject = printable(marking)
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    /** The text as it appears in the PDF: control characters removed, unsupported characters replaced by `?`. */
    public fun printable(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return buildString {
            normalized.forEach { c ->
                append(
                    when {
                        c == '\n' || c == '\r' || c == '\t' -> ' '
                        c.isISOControl() -> ' '
                        canShow(c) -> c
                        else -> '?'
                    },
                )
            }
        }
    }

    private val showable = HashMap<Char, Boolean>()

    @Synchronized
    private fun canShow(c: Char): Boolean =
        showable.getOrPut(c) {
            try {
                regular.encode(c.toString())
                bold.encode(c.toString())
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

    private fun drawText(
        cs: PDPageContentStream,
        font: PDType1Font,
        size: Float,
        x: Float,
        y: Float,
        text: String,
    ) {
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(text)
        cs.endText()
    }

    private fun interface Op {
        fun draw(cs: PDPageContentStream)
    }

    /** Lays the view out into pages of drawing operations (two passes: the footer needs the page count). */
    private class Layout(
        val box: PDRectangle,
    ) {
        val pages = mutableListOf<MutableList<Op>>(mutableListOf())
        private val width = box.width - 2 * MARGIN
        private var y = box.height - MARGIN - TOP_BAND

        fun render(view: InvoiceView) {
            heading(view.title, 15f, indent = 0f)
            y -= 6f
            view.sections.forEach { section(it, 0f) }
        }

        private fun section(
            section: ViewSection,
            indent: Float,
        ) {
            ensure(LEADING * 3)
            heading(section.title, if (indent == 0f) 12f else 10.5f, indent)
            section.fields.forEach { field(it, indent) }
            section.sections.forEach { section(it, indent + 12f) }
            y -= 4f
        }

        private fun heading(
            text: String,
            size: Float,
            indent: Float,
        ) {
            wrap(printable(text), bold, size, width - indent).forEach { line ->
                ensure(size + 4f)
                val at = y
                add { drawText(it, bold, size, MARGIN + indent, at, line) }
                y -= size + 4f
            }
        }

        private fun field(
            field: ViewField,
            indent: Float,
        ) {
            val labelWidth = (width - indent) * LABEL_SHARE
            val valueX = MARGIN + indent + labelWidth
            val valueWidth = width - indent - labelWidth
            val label = printable(field.label)
            val values = wrap(printable(field.value), regular, SIZE, valueWidth)
            // A label that doesn't fit its column gets a line of its own, so label and value
            // text never interleave (text extraction and screen readers read line by line).
            val labelFits = regular.getStringWidth(label) / 1000 * SIZE <= labelWidth - 6f
            ensure(LEADING * (if (labelFits) 1 else 2))
            if (!labelFits) {
                val at = y
                add { drawText(it, regular, SIZE, MARGIN + indent, at, label) }
                y -= LEADING
            }
            values.forEachIndexed { i, line ->
                ensure(LEADING)
                val at = y
                if (i == 0 && labelFits) add { drawText(it, regular, SIZE, MARGIN + indent, at, label) }
                add { drawText(it, regular, SIZE, valueX, at, line) }
                y -= LEADING
            }
        }

        private fun add(op: Op) {
            pages.last() += op
        }

        private fun ensure(height: Float) {
            if (y - height < MARGIN + BOTTOM_BAND) {
                pages += mutableListOf<Op>()
                y = box.height - MARGIN - TOP_BAND
            }
        }

        /** Breaks [text] into lines of at most [maxWidth]; words longer than a line are split. */
        private fun wrap(
            text: String,
            font: PDType1Font,
            size: Float,
            maxWidth: Float,
        ): List<String> {
            fun w(s: String) = font.getStringWidth(s) / 1000 * size
            val lines = mutableListOf<String>()
            var current = ""
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                var rest = word
                while (w(rest) > maxWidth) {
                    if (current.isNotEmpty()) {
                        lines += current
                        current = ""
                    }
                    var cut = rest.length - 1
                    while (cut > 1 && w(rest.substring(0, cut)) > maxWidth) cut--
                    lines += rest.substring(0, cut)
                    rest = rest.substring(cut)
                }
                val candidate = if (current.isEmpty()) rest else "$current $rest"
                if (w(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    lines += current
                    current = rest
                }
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines += current
            return lines
        }
    }
}
