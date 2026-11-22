package de.erika.invoicecore.xml

import de.erika.invoicecore.InvoiceParseException
import de.erika.invoicecore.ParseErrorCode
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XML parsing hardened against XXE and entity-expansion attacks (NF-04,
 * Software Design §7): DTDs are rejected outright, so neither external entities
 * nor "billion laughs" can be declared. Invoice XML never needs a DTD.
 */
internal object SafeXml {
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    private val factory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature(DISALLOW_DOCTYPE, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    fun parse(bytes: ByteArray): Document {
        val builder =
            synchronized(factory) { factory.newDocumentBuilder() }.apply {
                setErrorHandler(SilentErrorHandler)
                // Never resolve anything from outside the document.
                setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
            }
        return try {
            builder.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: SAXParseException) {
            if (e.message?.contains("DOCTYPE", ignoreCase = true) == true) {
                throw InvoiceParseException(ParseErrorCode.FORBIDDEN_XML_CONSTRUCT, "XML with a DOCTYPE declaration is not accepted", e)
            }
            throw InvoiceParseException(ParseErrorCode.MALFORMED_XML, "Malformed XML at line ${e.lineNumber}", e)
        } catch (e: org.xml.sax.SAXException) {
            throw InvoiceParseException(ParseErrorCode.MALFORMED_XML, "Malformed XML", e)
        }
    }

    /** True if the bytes look like XML (after an optional BOM and whitespace). */
    fun looksLikeXml(bytes: ByteArray): Boolean {
        var i = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        return i < bytes.size && bytes[i] == '<'.code.toByte()
    }

    private object SilentErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }
}
