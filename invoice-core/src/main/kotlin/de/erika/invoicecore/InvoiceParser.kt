package de.erika.invoicecore

import de.erika.invoicecore.cii.CiiMapper
import de.erika.invoicecore.model.DetectedFormat
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.NotEInvoiceReason
import de.erika.invoicecore.model.ParsedDocument
import de.erika.invoicecore.model.Profile
import de.erika.invoicecore.model.Syntax
import de.erika.invoicecore.pdf.PdfInvoiceExtractor
import de.erika.invoicecore.ubl.UblMapper
import de.erika.invoicecore.xml.Ns
import de.erika.invoicecore.xml.SafeXml
import de.erika.invoicecore.xml.Warnings
import de.erika.invoicecore.xml.root

/**
 * Entry point: reads XRechnung (UBL/CII), other EN 16931 UBL/CII invoices and
 * ZUGFeRD 2.x / Factur-X hybrid PDFs into the neutral [Invoice] model (IN-03, VW-01).
 *
 * Thread-safe and stateless. Throws [InvoiceParseException] only when the file
 * can't be read at all; a readable file that isn't an e-invoice (plain PDF,
 * ZUGFeRD 1.0, unknown XML) returns a [ParsedDocument] whose format says so.
 */
public object InvoiceParser {
    /** Maximum accepted file size (Software Design §7). */
    public const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024

    /** Maximum number of invoice lines, including sub lines (Software Design §7). */
    public const val MAX_LINES: Int = 5_000

    public fun parse(bytes: ByteArray): ParsedDocument {
        if (bytes.size > MAX_FILE_BYTES) {
            throw InvoiceParseException(ParseErrorCode.TOO_LARGE, "File is larger than $MAX_FILE_BYTES bytes")
        }
        return when {
            PdfInvoiceExtractor.isPdf(bytes) -> parsePdf(bytes)
            SafeXml.looksLikeXml(bytes) -> parseXml(bytes, embeddedFileName = null)
            else -> throw InvoiceParseException(ParseErrorCode.UNKNOWN_FILE_TYPE, "File is neither XML nor PDF")
        }
    }

    /**
     * The invoice XML to validate: the file itself for XML, the embedded invoice XML
     * (ZUGFeRD/Factur-X) for a PDF, or null for a PDF without one. The bytes are not
     * checked to be an invoice; use [parse] for that.
     */
    public fun invoiceXml(bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_FILE_BYTES) {
            throw InvoiceParseException(ParseErrorCode.TOO_LARGE, "File is larger than $MAX_FILE_BYTES bytes")
        }
        return when {
            PdfInvoiceExtractor.isPdf(bytes) -> PdfInvoiceExtractor.extract(bytes)?.bytes
            SafeXml.looksLikeXml(bytes) -> bytes
            else -> throw InvoiceParseException(ParseErrorCode.UNKNOWN_FILE_TYPE, "File is neither XML nor PDF")
        }
    }

    /** Detects the format without keeping the model (same cost as [parse]). */
    public fun detect(bytes: ByteArray): DetectedFormat = parse(bytes).format

    /** Maps a specification identifier (BT-24) to a profile. */
    public fun profileOf(specificationIdentifier: String?): Profile {
        val s = specificationIdentifier?.trim()?.lowercase() ?: return Profile.UNKNOWN
        return when {
            "xrechnung" in s -> Profile.XRECHNUNG
            "basicwl" in s -> Profile.BASIC_WL
            ":minimum" in s -> Profile.MINIMUM
            ":extended" in s -> Profile.EXTENDED
            s.endsWith(":basic") -> Profile.BASIC
            "en16931" in s -> Profile.EN16931
            else -> Profile.UNKNOWN
        }
    }

    private fun parsePdf(bytes: ByteArray): ParsedDocument {
        val embedded =
            PdfInvoiceExtractor.extract(bytes)
                ?: return ParsedDocument(DetectedFormat(InvoiceFormat.PDF_ONLY, reason = NotEInvoiceReason.PLAIN_PDF), null)
        return parseXml(embedded.bytes, embedded.fileName)
    }

    private fun parseXml(
        bytes: ByteArray,
        embeddedFileName: String?,
    ): ParsedDocument {
        val root = SafeXml.parse(bytes).root()
        val ns = root.element.namespaceURI
        val warnings = Warnings()
        val (syntax, invoice) =
            when {
                (ns == Ns.UBL_INVOICE && root.localName == "Invoice") || (ns == Ns.UBL_CREDIT_NOTE && root.localName == "CreditNote") -> {
                    UblMapper(root, warnings).let { Syntax.UBL to it.also { m -> checkLines(m.lineCount()) }.map() }
                }

                ns == Ns.RSM && root.localName == "CrossIndustryInvoice" -> {
                    CiiMapper(root, warnings).let { Syntax.CII to it.also { m -> checkLines(m.lineCount()) }.map() }
                }

                else -> {
                    return unsupported(ns, root.localName, isZugferd1Guideline(root.element), embeddedFileName)
                }
            }
        val profile = profileOf(invoice.specificationIdentifier)
        val format =
            when {
                embeddedFileName != null -> InvoiceFormat.ZUGFERD
                syntax == Syntax.UBL -> if (profile == Profile.XRECHNUNG) InvoiceFormat.XRECHNUNG_UBL else InvoiceFormat.UBL
                else -> if (profile == Profile.XRECHNUNG) InvoiceFormat.XRECHNUNG_CII else InvoiceFormat.CII
            }
        val reason =
            when (profile) {
                Profile.MINIMUM, Profile.BASIC_WL -> NotEInvoiceReason.INSUFFICIENT_PROFILE
                Profile.UNKNOWN -> NotEInvoiceReason.UNKNOWN_PROFILE
                else -> null
            }
        return ParsedDocument(
            DetectedFormat(format, syntax, profile, invoice.specificationIdentifier, embeddedFileName, reason),
            invoice,
            warnings.toList(),
        )
    }

    /**
     * ZUGFeRD 1.0 has root `rsm:CrossIndustryDocument`; its namespace varies between
     * producers, and pre-release files use other roots but a `urn:ferd:` guideline ID.
     */
    private fun unsupported(
        ns: String?,
        rootName: String,
        ferdGuideline: Boolean,
        embeddedFileName: String?,
    ): ParsedDocument {
        val zugferd1 = ns == Ns.ZUGFERD_1 || rootName == "CrossIndustryDocument" || ferdGuideline
        val reason = if (zugferd1) NotEInvoiceReason.ZUGFERD_1 else NotEInvoiceReason.UNKNOWN_XML
        return ParsedDocument(DetectedFormat(InvoiceFormat.UNSUPPORTED, embeddedFileName = embeddedFileName, reason = reason), null)
    }

    private fun isZugferd1Guideline(root: org.w3c.dom.Element): Boolean {
        val params = root.getElementsByTagNameNS("*", "GuidelineSpecifiedDocumentContextParameter")
        return (0 until params.length).any {
            params
                .item(it)
                .textContent
                .trim()
                .startsWith("urn:ferd:")
        }
    }

    private fun checkLines(count: Int) {
        if (count > MAX_LINES) {
            throw InvoiceParseException(ParseErrorCode.TOO_MANY_LINES, "Invoice has $count lines; at most $MAX_LINES are accepted")
        }
    }
}
