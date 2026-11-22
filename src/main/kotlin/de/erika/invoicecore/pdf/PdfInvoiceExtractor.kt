package de.erika.invoicecore.pdf

import de.erika.invoicecore.InvoiceParseException
import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.ParseErrorCode
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.IOException

/** XML file found inside a hybrid PDF (ZUGFeRD 2.x / Factur-X / XRechnung in PDF). */
internal class EmbeddedXml(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Finds the invoice XML attached to a PDF/A-3. Looks for the standard file names
 * first and falls back to any attached `.xml` file. Memory is capped; time limits
 * are the caller's job (the ERIKA worker runs parsing with a timeout).
 */
internal object PdfInvoiceExtractor {
    /** File names defined by ZUGFeRD 2.x, Factur-X and the XRechnung-in-PDF convention. */
    private val STANDARD_NAMES = listOf("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml", "order-x.xml")
    private const val MAX_PDF_MEMORY: Long = 256L * 1024 * 1024

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == '-'.code.toByte()

    fun extract(bytes: ByteArray): EmbeddedXml? =
        try {
            Loader.loadPDF(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_PDF_MEMORY).streamCache).use(::findInvoiceXml)
        } catch (e: InvalidPasswordException) {
            throw InvoiceParseException(ParseErrorCode.MALFORMED_PDF, "PDF is password-protected", e)
        } catch (e: IOException) {
            throw InvoiceParseException(ParseErrorCode.MALFORMED_PDF, "PDF can't be read", e)
        }

    private fun findInvoiceXml(doc: PDDocument): EmbeddedXml? {
        val files = embeddedFiles(doc.documentCatalog.names?.embeddedFiles)
        if (files.isEmpty()) return null
        val chosen =
            STANDARD_NAMES.firstNotNullOfOrNull { name -> files.entries.firstOrNull { it.key.equals(name, ignoreCase = true) } }
                ?: files.entries.firstOrNull { it.key.endsWith(".xml", ignoreCase = true) }
                ?: return null
        val spec = chosen.value
        val file = spec.embeddedFileUnicode ?: spec.embeddedFile ?: return null
        if (file.length > InvoiceParser.MAX_FILE_BYTES) {
            throw InvoiceParseException(ParseErrorCode.TOO_LARGE, "Embedded XML is larger than ${InvoiceParser.MAX_FILE_BYTES} bytes")
        }
        return EmbeddedXml(chosen.key, file.toByteArray())
    }

    /** Flattens the (possibly nested) embedded-files name tree. */
    private fun embeddedFiles(node: PDNameTreeNode<PDComplexFileSpecification>?): Map<String, PDComplexFileSpecification> {
        if (node == null) return emptyMap()
        val result = linkedMapOf<String, PDComplexFileSpecification>()
        node.names?.let { result.putAll(it) }
        node.kids?.forEach { result.putAll(embeddedFiles(it)) }
        return result
    }
}
