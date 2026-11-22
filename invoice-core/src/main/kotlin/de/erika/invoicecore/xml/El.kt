package de.erika.invoicecore.xml

import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.ParseWarning
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Namespaces used by the UBL and CII syntax bindings of EN 16931. */
internal object Ns {
    const val UBL_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    const val UBL_CREDIT_NOTE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
    const val CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    const val CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    const val RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
    const val RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
    const val UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100"
    const val QDT = "urn:un:unece:uncefact:data:standard:QualifiedDataType:100"
    const val ZUGFERD_1 = "urn:ferd:CrossIndustryDocument:invoice:1p0"

    private val prefixes = mapOf("cbc" to CBC, "cac" to CAC, "rsm" to RSM, "ram" to RAM, "udt" to UDT, "qdt" to QDT)

    fun uri(prefix: String): String = prefixes[prefix] ?: error("Unknown namespace prefix '$prefix'")
}

/**
 * Collects non-fatal problems while mapping. Messages never contain invoice
 * content, only the business term and the kind of problem.
 */
internal class Warnings {
    private val list = mutableListOf<ParseWarning>()

    fun add(
        code: String,
        businessTerm: String?,
        message: String,
    ) {
        list += ParseWarning(code, businessTerm, message)
    }

    fun toList(): List<ParseWarning> = list.toList()
}

/**
 * Minimal navigation over DOM elements using `prefix:local` paths separated by `/`.
 * `child` returns the first match, `children` all matches across every branch.
 */
@JvmInline
internal value class El(
    val element: Element,
) {
    fun child(path: String): El? = children(path).firstOrNull()

    fun children(path: String): List<El> {
        var current = listOf(this)
        for (step in path.split('/')) {
            val (prefix, local) = step.split(':', limit = 2).let { it[0] to it[1] }
            val uri = Ns.uri(prefix)
            current = current.flatMap { it.directChildren(uri, local) }
            if (current.isEmpty()) break
        }
        return current
    }

    /** Trimmed text of the first element at [path], or null if missing or blank. */
    fun text(path: String): String? = child(path)?.text

    val text: String?
        get() = element.textContent?.trim()?.takeIf { it.isNotEmpty() }

    val localName: String get() = element.localName

    fun attr(name: String): String? = element.getAttribute(name).trim().takeIf { it.isNotEmpty() }

    /** Identifier from element text plus a scheme attribute. */
    fun identifier(
        path: String,
        schemeAttr: String = "schemeID",
    ): Identifier? = child(path)?.asIdentifier(schemeAttr)

    fun asIdentifier(
        schemeAttr: String = "schemeID",
        versionAttr: String? = null,
    ): Identifier? = text?.let { Identifier(it, attr(schemeAttr), versionAttr?.let(::attr)) }

    fun decimal(
        path: String,
        bt: String,
        warnings: Warnings,
    ): BigDecimal? = child(path)?.decimal(bt, warnings)

    fun decimal(
        bt: String,
        warnings: Warnings,
    ): BigDecimal? {
        val raw = text ?: return null
        return try {
            BigDecimal(raw)
        } catch (_: NumberFormatException) {
            warnings.add("INVALID_DECIMAL", bt, "Value of $bt is not a valid decimal number")
            null
        }
    }

    /** ISO 8601 date (UBL `cbc:*Date`). */
    fun isoDate(
        path: String,
        bt: String,
        warnings: Warnings,
    ): LocalDate? {
        val raw = text(path) ?: return null
        return parseDate(raw, null, bt, warnings)
    }

    /** CII date: element with text and a `format` attribute (102 = yyyyMMdd). */
    fun ciiDate(
        path: String,
        bt: String,
        warnings: Warnings,
    ): LocalDate? {
        val el = child(path) ?: return null
        val raw = el.text ?: return null
        return parseDate(raw, el.attr("format"), bt, warnings)
    }

    private fun directChildren(
        uri: String,
        local: String,
    ): List<El> {
        val result = mutableListOf<El>()
        var node: Node? = element.firstChild
        while (node != null) {
            if (node is Element && node.localName == local && node.namespaceURI == uri) result += El(node)
            node = node.nextSibling
        }
        return result
    }

    private companion object {
        val BASIC: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun parseDate(
            raw: String,
            format: String?,
            bt: String,
            warnings: Warnings,
        ): LocalDate? =
            try {
                when {
                    format == "102" || (format == null && raw.length == 8 && raw.all(Char::isDigit)) -> LocalDate.parse(raw, BASIC)
                    else -> LocalDate.parse(raw.take(10))
                }
            } catch (_: DateTimeParseException) {
                warnings.add("INVALID_DATE", bt, "Value of $bt is not a valid date (format ${format ?: "ISO 8601"})")
                null
            }
    }
}

/** Root element of a parsed document. */
internal fun org.w3c.dom.Document.root(): El = El(documentElement)
