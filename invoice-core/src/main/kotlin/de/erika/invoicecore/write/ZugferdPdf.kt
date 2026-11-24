package de.erika.invoicecore.write

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.view.CodeLists
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * Builds a ZUGFeRD 2.x / Factur-X hybrid (profile EN 16931): a PDF/A-3b invoice that people can
 * read, with the CII XML embedded as `factur-x.xml` (AFRelationship `Alternative`) and the
 * Factur-X XMP extension schema (ADR 0006).
 *
 * PDF/A-3b needs embedded fonts and an output intent: Liberation Sans (shipped with PDFBox)
 * and the JDK's sRGB profile. The output is deterministic: dates come from the invoice, and
 * the document ID is derived from the embedded XML, so the same invoice gives the same bytes.
 */
internal object ZugferdPdf {
    const val XML_FILE_NAME = "factur-x.xml"
    private const val PRODUCER = "ERIKA invoice-core"
    private const val CREATOR = "ERIKA"

    fun write(
        invoice: Invoice,
        xml: ByteArray,
        layout: PdfLayout = PdfLayout.DEFAULT,
    ): ByteArray =
        PDDocument().use { doc ->
            val font = loadFont(doc)
            val pages = Layout(invoice, font, layout.accentRgb).build()
            val logo = layout.logo?.let { image(doc, it) }
            pages.forEachIndexed { index, ops ->
                val page = PDPage(PDRectangle.A4).also(doc::addPage)
                PDPageContentStream(doc, page).use { cs ->
                    ops.forEach { it(cs) }
                    if (index == 0 && logo != null) drawLogo(cs, logo)
                    Layout.footer(cs, font, invoice, index + 1, pages.size)
                }
            }
            val date = calendar(invoice.issueDate ?: LocalDate.of(2000, 1, 1))
            val title = "${titleOf(invoice)} ${invoice.number ?: ""}".trim()
            doc.documentInformation.apply {
                this.title = Text.clean(title)
                producer = PRODUCER
                creator = CREATOR
                creationDate = date
                modificationDate = date
            }
            embedXml(doc, xml, date)
            doc.documentCatalog.metadata = PDMetadata(doc).apply { importXMPMetadata(xmp(Text.clean(title), invoice.issueDate)) }
            doc.documentCatalog.addOutputIntent(
                PDOutputIntent(doc, ICC_Profile.getInstance(ColorSpace.CS_sRGB).data.inputStream()).apply {
                    info = "sRGB IEC61966-2.1"
                    outputCondition = "sRGB IEC61966-2.1"
                    outputConditionIdentifier = "sRGB IEC61966-2.1"
                    registryName = "http://www.color.org"
                },
            )
            val id = COSString(MessageDigest.getInstance("MD5").digest(xml))
            doc.document.trailer.setItem(COSName.ID, COSArray(listOf(id, id)))
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }

    fun titleOf(invoice: Invoice): String =
        when (invoice.typeCode) {
            Invoice.TYPE_CREDIT_NOTE -> "Gutschrift"
            Invoice.TYPE_CORRECTED_INVOICE -> "Rechnungskorrektur"
            else -> "Rechnung"
        }

    /** The logo as an image XObject: JPEG passed through, PNG re-encoded losslessly (with its alpha channel). */
    private fun image(
        doc: PDDocument,
        bytes: ByteArray,
    ): PDImageXObject =
        when (PdfLayout.imageType(bytes)) {
            "image/jpeg" -> JPEGFactory.createFromByteArray(doc, bytes)
            else -> LosslessFactory.createFromImage(doc, requireNotNull(ImageIO.read(bytes.inputStream())) { "Unreadable logo" })
        }

    /** Top right of the first page, inside 160 × 44 pt, aspect ratio kept. */
    private fun drawLogo(
        cs: PDPageContentStream,
        image: PDImageXObject,
    ) {
        val scale = minOf(LOGO_MAX_W / image.width, LOGO_MAX_H / image.height)
        val w = image.width * scale
        val h = image.height * scale
        cs.drawImage(image, Layout.RIGHT - w, LOGO_TOP - h, w, h)
    }

    private const val LOGO_MAX_W = 160f
    private const val LOGO_MAX_H = 44f
    private const val LOGO_TOP = 812f

    private fun loadFont(doc: PDDocument): PDType0Font =
        PDDocument::class.java.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")!!.use {
            PDType0Font.load(doc, it)
        }

    private fun calendar(date: LocalDate): GregorianCalendar =
        GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC)).apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
        }

    private fun embedXml(
        doc: PDDocument,
        xml: ByteArray,
        date: GregorianCalendar,
    ) {
        val embedded =
            PDEmbeddedFile(doc, xml.inputStream()).apply {
                subtype = "text/xml"
                size = xml.size
                creationDate = date
                modDate = date
            }
        val spec =
            PDComplexFileSpecification().apply {
                file = XML_FILE_NAME
                fileUnicode = XML_FILE_NAME
                fileDescription = "Rechnungsdaten nach EN 16931 (ZUGFeRD/Factur-X)"
                embeddedFile = embedded
                embeddedFileUnicode = embedded
                cosObject.setName(COSName.getPDFName("AFRelationship"), "Alternative")
            }
        val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf(XML_FILE_NAME to spec) }
        val names = doc.documentCatalog.names ?: PDDocumentNameDictionary(doc.documentCatalog)
        names.embeddedFiles = tree
        doc.documentCatalog.names = names
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("AF"), COSArray(listOf(spec.cosObject)))
    }

    private fun xmp(
        title: String,
        issueDate: LocalDate?,
    ): ByteArray {
        val date = (issueDate ?: LocalDate.of(2000, 1, 1)).toString() + "T00:00:00Z"
        val e = XmlOut::escape
        val property = { name: String, description: String ->
            """
            |            <rdf:li rdf:parseType="Resource">
            |              <pdfaProperty:name>$name</pdfaProperty:name>
            |              <pdfaProperty:valueType>Text</pdfaProperty:valueType>
            |              <pdfaProperty:category>external</pdfaProperty:category>
            |              <pdfaProperty:description>$description</pdfaProperty:description>
            |            </rdf:li>
            """.trimMargin()
        }
        val xmp =
            """
            |<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/">
            |  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |    <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
            |      <pdfaid:part>3</pdfaid:part>
            |      <pdfaid:conformance>B</pdfaid:conformance>
            |    </rdf:Description>
            |    <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
            |      <dc:title><rdf:Alt><rdf:li xml:lang="x-default">${e(title)}</rdf:li></rdf:Alt></dc:title>
            |    </rdf:Description>
            |    <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
            |      <xmp:CreatorTool>$CREATOR</xmp:CreatorTool>
            |      <xmp:CreateDate>$date</xmp:CreateDate>
            |      <xmp:ModifyDate>$date</xmp:ModifyDate>
            |    </rdf:Description>
            |    <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
            |      <pdf:Producer>$PRODUCER</pdf:Producer>
            |    </rdf:Description>
            |    <rdf:Description rdf:about="" xmlns:fx="urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#">
            |      <fx:DocumentType>INVOICE</fx:DocumentType>
            |      <fx:DocumentFileName>$XML_FILE_NAME</fx:DocumentFileName>
            |      <fx:Version>1.0</fx:Version>
            |      <fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>
            |    </rdf:Description>
            |    <rdf:Description rdf:about="" xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/"
            |        xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#" xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
            |      <pdfaExtension:schemas>
            |        <rdf:Bag>
            |          <rdf:li rdf:parseType="Resource">
            |            <pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>
            |            <pdfaSchema:namespaceURI>urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#</pdfaSchema:namespaceURI>
            |            <pdfaSchema:prefix>fx</pdfaSchema:prefix>
            |            <pdfaSchema:property>
            |              <rdf:Seq>
            |${property("DocumentFileName", "name of the embedded XML invoice file")}
            |${property("DocumentType", "INVOICE")}
            |${property("Version", "The actual version of the Factur-X XML schema")}
            |${property("ConformanceLevel", "The conformance level of the embedded Factur-X data")}
            |              </rdf:Seq>
            |            </pdfaSchema:property>
            |          </rdf:li>
            |        </rdf:Bag>
            |      </pdfaExtension:schemas>
            |    </rdf:Description>
            |  </rdf:RDF>
            |</x:xmpmeta>
            |<?xpacket end="w"?>
            """.trimMargin()
        return xmp.toByteArray(Charsets.UTF_8)
    }
}

/** Text helpers for the visual invoice: German number and date formats, safe characters. */
internal object Text {
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun date(d: LocalDate?): String = d?.format(DATE) ?: ""

    fun money(
        v: BigDecimal?,
        currency: String?,
    ): String {
        if (v == null) return ""
        val f = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.GERMANY))
        return f.format(v) + (if (currency == null || currency == "EUR") " €" else " $currency")
    }

    fun number(v: BigDecimal?): String {
        if (v == null) return ""
        val f = DecimalFormat("#,##0.####", DecimalFormatSymbols(Locale.GERMANY))
        return f.format(v)
    }

    fun percent(v: BigDecimal?): String = if (v == null) "" else number(v) + " %"

    /** NFC, no control characters (the font check in [Layout] replaces what the font can't show). */
    fun clean(s: String): String =
        Normalizer
            .normalize(s, Normalizer.Form.NFC)
            .replace('\t', ' ')
            .filter { !it.isISOControl() || it == '\n' }

    fun unit(code: String?): String = code?.let { CodeLists.unit[it] ?: it } ?: ""

    fun addressLines(a: Address?): List<String> {
        if (a == null) return emptyList()
        val city = listOfNotNull(a.postCode, a.city).joinToString(" ")
        return listOfNotNull(a.line1, a.line2, a.line3, city.ifBlank { null }, a.countryCode?.takeIf { it != "DE" })
    }
}

private typealias Op = (PDPageContentStream) -> Unit

/** Lays the invoice out on A4 pages; page breaks repeat the table header. */
private class Layout(
    private val invoice: Invoice,
    private val font: PDType0Font,
    private val accent: FloatArray? = null,
) {
    /** Draws what [block] adds in the accent colour (if any), then returns to black. */
    private fun accented(block: () -> Unit) {
        val rgb = accent ?: return block()
        ops += { cs ->
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
        }
        block()
        ops += { cs ->
            cs.setNonStrokingColor(0f, 0f, 0f)
            cs.setStrokingColor(0f, 0f, 0f)
        }
    }

    private val pages = mutableListOf<MutableList<Op>>()
    private var ops = mutableListOf<Op>()
    private var y = 0f
    private val cur = invoice.currency

    fun build(): List<List<Op>> {
        newPage(first = true)
        header()
        table()
        totals()
        notes()
        payment()
        pages += ops
        return pages
    }

    private fun newPage(first: Boolean = false) {
        if (!first) pages += ops
        ops = mutableListOf()
        y = TOP
    }

    private fun ensure(space: Float) {
        if (y - space < BOTTOM) {
            newPage()
            tableHeader()
        }
    }

    private fun text(
        x: Float,
        yPos: Float,
        s: String,
        size: Float = SIZE,
        right: Boolean = false,
    ) {
        val t = safe(s)
        if (t.isEmpty()) return
        val px = if (right) x - width(t, size) else x
        ops += { cs -> draw(cs, font, size, px, yPos, t) }
    }

    private fun safe(s: String) = safeText(font, s)

    private fun width(
        s: String,
        size: Float,
    ) = font.getStringWidth(s) / 1000f * size

    private fun header() {
        val s = invoice.seller
        text(LEFT, y, s.name ?: "", 15f)
        y -= 26f
        val sender = (listOfNotNull(s.name) + Text.addressLines(s.address)).joinToString(" · ")
        text(LEFT, y, sender, 7f)
        y -= 14f
        val top = y
        val b = invoice.buyer
        (listOfNotNull(b.name) + Text.addressLines(b.address)).forEach {
            text(LEFT, y, it, 10f)
            y -= 13f
        }
        var my = top
        meta().forEach { (label, value) ->
            text(META_X, my, label, 8.5f)
            text(RIGHT, my, value, 8.5f, right = true)
            my -= 12f
        }
        y = minOf(y, my) - 24f
        accented { text(LEFT, y, "${ZugferdPdf.titleOf(invoice)} ${invoice.number ?: ""}", 14f) }
        y -= 18f
        invoice.precedingInvoices.firstOrNull()?.let { ref ->
            text(LEFT, y, "Bezieht sich auf Rechnung ${ref.number}" + (ref.issueDate?.let { " vom ${Text.date(it)}" } ?: ""))
            y -= 14f
        }
        y -= 6f
        tableHeader()
    }

    private fun meta(): List<Pair<String, String>> =
        buildList {
            add("Nummer" to (invoice.number ?: ""))
            add("Datum" to Text.date(invoice.issueDate))
            val d = invoice.delivery
            d?.actualDeliveryDate?.let { add("Leistungsdatum" to Text.date(it)) }
            d?.invoicingPeriod?.let { add("Leistungszeitraum" to "${Text.date(it.start)} – ${Text.date(it.end)}") }
            invoice.dueDate?.let { add("Fällig am" to Text.date(it)) }
            invoice.buyer.identifier?.let { add("Kundennummer" to it.value) }
            invoice.buyerReference?.let { add("Ihre Referenz" to it) }
            invoice.purchaseOrderReference?.let { add("Bestellnummer" to it) }
            invoice.buyer.vatId?.let { add("USt-IdNr. Kunde" to it) }
        }

    private fun tableHeader() {
        val h = 8.5f
        text(LEFT, y, "Pos.", h)
        text(COL_NAME, y, "Bezeichnung", h)
        text(COL_QTY, y, "Menge", h, right = true)
        text(COL_UNIT, y, "Einheit", h)
        text(COL_PRICE, y, "Einzelpreis", h, right = true)
        text(COL_VAT, y, "USt.", h, right = true)
        text(RIGHT, y, "Betrag", h, right = true)
        val lineY = y - 4f
        accented { ops += { cs -> rule(cs, lineY) } }
        y -= 16f
    }

    private fun table() {
        invoice.lines.forEachIndexed { i, line ->
            val nameLines = wrap(line.item.name ?: "", COL_QTY - 40f - COL_NAME, SIZE)
            val descLines = line.item.description?.let { wrap(it, COL_QTY - 40f - COL_NAME, 8f) } ?: emptyList()
            val period = line.period?.let { listOf("Zeitraum ${Text.date(it.start)} – ${Text.date(it.end)}") } ?: emptyList()
            ensure(LEADING * (nameLines.size + descLines.size + period.size) + 4f)
            text(LEFT, y, line.id ?: (i + 1).toString())
            text(COL_QTY, y, Text.number(line.quantity), right = true)
            text(COL_UNIT, y, Text.unit(line.unitCode))
            text(COL_PRICE, y, Text.money(line.price.netPrice, cur), right = true)
            text(COL_VAT, y, Text.percent(line.vatRate), right = true)
            text(RIGHT, y, Text.money(line.netAmount, cur), right = true)
            nameLines.forEach {
                text(COL_NAME, y, it)
                y -= LEADING
            }
            (descLines + period).forEach {
                text(COL_NAME, y, it, 8f)
                y -= LEADING - 1f
            }
            y -= 3f
        }
        val ruleY = y + 8f
        ops += { cs -> rule(cs, ruleY) }
        y -= 8f
    }

    private fun totals() {
        val t = invoice.totals
        val rows =
            buildList {
                add("Summe netto" to Text.money(t.lineNetTotal, cur))
                t.allowanceTotal?.let { add("Nachlässe" to "-" + Text.money(it, cur)) }
                t.chargeTotal?.let { add("Zuschläge" to Text.money(it, cur)) }
                invoice.vatBreakdown.forEach { v ->
                    val label =
                        if (v.categoryCode == "S") {
                            "Umsatzsteuer ${Text.percent(v.rate)} auf ${Text.money(v.taxableAmount, cur)}"
                        } else {
                            "${CodeLists.vatCategory[v.categoryCode] ?: v.categoryCode} (${Text.money(v.taxableAmount, cur)})"
                        }
                    add(label to Text.money(v.taxAmount, cur))
                }
                add("Gesamtbetrag" to Text.money(t.totalWithVat, cur))
                t.paidAmount?.let { add("Bereits gezahlt" to Text.money(it, cur)) }
                t.roundingAmount?.let { add("Rundung" to Text.money(it, cur)) }
                if (t.amountDue != null && t.amountDue.compareTo(t.totalWithVat) != 0) add("Zahlbetrag" to Text.money(t.amountDue, cur))
            }
        ensure(rows.size * LEADING + 8f)
        rows.forEach { (label, value) ->
            val strong = label == "Gesamtbetrag" || label == "Zahlbetrag"
            text(COL_UNIT - 60f, y, label, if (strong) 10f else SIZE)
            text(RIGHT, y, value, if (strong) 10f else SIZE, right = true)
            y -= LEADING + (if (strong) 2f else 0f)
        }
        y -= 10f
    }

    private fun paragraph(
        s: String,
        size: Float = SIZE,
    ) {
        s.split('\n').forEach { para ->
            wrap(para, RIGHT - LEFT, size).forEach {
                ensure(LEADING)
                text(LEFT, y, it, size)
                y -= LEADING
            }
        }
        y -= 4f
    }

    private fun notes() {
        invoice.notes.forEach { paragraph(it.text) }
    }

    private fun payment() {
        invoice.paymentTerms?.let { paragraph(it) }
        val p = invoice.paymentInstructions ?: return
        p.creditTransfers.forEach { ct ->
            val parts =
                listOfNotNull(
                    "Bankverbindung: IBAN ${ct.accountId}",
                    ct.serviceProviderId?.let { "BIC $it" },
                    ct.accountName?.let { "Kontoinhaber $it" },
                )
            paragraph(parts.joinToString(" · "))
        }
        p.remittanceInformation?.let { paragraph("Verwendungszweck: $it") }
    }

    private fun wrap(
        s: String,
        maxWidth: Float,
        size: Float,
    ): List<String> {
        val words = safe(s).split(' ').filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate, size) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.ifEmpty { listOf("") }
    }

    companion object {
        const val LEFT = 56f
        const val RIGHT = 539f
        const val META_X = 360f
        const val TOP = 790f
        const val BOTTOM = 90f
        const val SIZE = 9f
        const val LEADING = 12f
        const val COL_NAME = 84f
        const val COL_QTY = 360f
        const val COL_UNIT = 368f
        const val COL_PRICE = 462f
        const val COL_VAT = 492f

        fun rule(
            cs: PDPageContentStream,
            y: Float,
        ) {
            cs.setLineWidth(0.5f)
            cs.moveTo(LEFT, y)
            cs.lineTo(RIGHT, y)
            cs.stroke()
        }

        fun footer(
            cs: PDPageContentStream,
            font: PDType0Font,
            invoice: Invoice,
            page: Int,
            pages: Int,
        ) {
            val s = invoice.seller
            val lines =
                listOf(
                    (listOfNotNull(s.name) + Text.addressLines(s.address)).joinToString(" · "),
                    listOfNotNull(
                        s.vatId?.let { "USt-IdNr. $it" },
                        s.taxRegistrationId?.let { "Steuernummer $it" },
                        s.legalRegistrationId?.let { "Register ${it.value}" },
                        s.contact?.phone?.let { "Tel. $it" },
                        s.contact?.email,
                    ).joinToString(" · "),
                    "Diese PDF-Datei enthält die Rechnungsdaten nach EN 16931 ($XML_NAME). Seite $page von $pages",
                )
            rule(cs, 62f)
            lines.forEachIndexed { i, l -> draw(cs, font, 7f, LEFT, 52f - i * 9f, safeText(font, l)) }
        }

        private const val XML_NAME = ZugferdPdf.XML_FILE_NAME
    }
}

private fun draw(
    cs: PDPageContentStream,
    font: PDType0Font,
    size: Float,
    x: Float,
    y: Float,
    text: String,
) {
    if (text.isEmpty()) return
    cs.beginText()
    cs.setFont(font, size)
    cs.newLineAtOffset(x, y)
    cs.showText(text)
    cs.endText()
}

/** The text with every character the font can't show replaced by `?`. */
private fun safeText(
    font: PDType0Font,
    s: String,
): String =
    buildString {
        Text.clean(s).replace('\n', ' ').forEach { c ->
            append(if (font.hasGlyphFor(c)) c else '?')
        }
    }

private fun PDType0Font.hasGlyphFor(c: Char): Boolean =
    try {
        encode(c.toString())
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: java.io.IOException) {
        false
    }
