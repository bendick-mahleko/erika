package de.erika.invoicecore.write

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Minimal, deterministic XML writer for the invoice serialisers: UTF-8, two-space indent,
 * attributes in the order given, text escaped. Elements without content are left out by the
 * `opt…` helpers, so callers never write empty elements (the schemas forbid most of them).
 *
 * The same input always gives the same bytes, so issuing an invoice twice (job retry)
 * archives the same original.
 */
internal class XmlOut(
    rootName: String,
    namespaces: List<Pair<String, String>>,
    rootAttributes: List<Pair<String, String>> = emptyList(),
) {
    private val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    private var depth = 0

    init {
        out.append('<').append(rootName)
        namespaces.forEach { (prefix, uri) ->
            out
                .append(if (prefix.isEmpty()) " xmlns" else " xmlns:$prefix")
                .append("=\"")
                .append(escape(uri))
                .append('"')
        }
        rootAttributes.forEach { (k, v) ->
            out
                .append(' ')
                .append(k)
                .append("=\"")
                .append(escape(v))
                .append('"')
        }
        out.append(">\n")
        depth = 1
    }

    private val rootName = rootName

    /** A container element; [body] writes its children. Left out entirely if [body] writes nothing. */
    fun el(
        name: String,
        vararg attrs: Pair<String, String?>,
        body: XmlOut.() -> Unit,
    ) {
        val mark = out.length
        val markDepth = depth
        indent()
        out.append('<').append(name)
        attrs.forEach { (k, v) ->
            if (v != null) {
                out
                    .append(' ')
                    .append(k)
                    .append("=\"")
                    .append(escape(v))
                    .append('"')
            }
        }
        out.append(">\n")
        val childStart = out.length
        depth++
        body()
        depth--
        if (out.length == childStart) {
            out.setLength(mark)
            depth = markDepth
            return
        }
        indent()
        out.append("</").append(name).append(">\n")
    }

    /** A container element written even when empty (e.g. `ram:ApplicableHeaderTradeDelivery`). */
    fun required(
        name: String,
        body: XmlOut.() -> Unit,
    ) {
        val before = out.length
        el(name, body = body)
        if (out.length == before) {
            indent()
            out.append('<').append(name).append("/>\n")
        }
    }

    /** A text element; null or blank values are left out. */
    fun text(
        name: String,
        value: String?,
        vararg attrs: Pair<String, String?>,
    ) {
        if (value.isNullOrBlank()) return
        indent()
        out.append('<').append(name)
        attrs.forEach { (k, v) ->
            if (v != null) {
                out
                    .append(' ')
                    .append(k)
                    .append("=\"")
                    .append(escape(v))
                    .append('"')
            }
        }
        out
            .append('>')
            .append(escape(value.trim()))
            .append("</")
            .append(name)
            .append(">\n")
    }

    fun amount(
        name: String,
        value: BigDecimal?,
        vararg attrs: Pair<String, String?>,
    ) = text(name, value?.let(::plain), *attrs)

    fun finish(): ByteArray {
        out.append("</").append(rootName).append(">\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun indent() {
        repeat(depth) { out.append("  ") }
    }

    companion object {
        val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** Amounts as written in the file: plain notation, at least two decimals for money. */
        fun plain(value: BigDecimal): String = value.toPlainString()

        fun basic(date: LocalDate): String = date.format(BASIC_DATE)

        fun escape(s: String): String {
            val b = StringBuilder(s.length)
            for (c in s) {
                when {
                    c == '&' -> b.append("&amp;")

                    c == '<' -> b.append("&lt;")

                    c == '>' -> b.append("&gt;")

                    c == '"' -> b.append("&quot;")

                    c == '\n' || c == '\t' -> b.append(c)

                    c == '\r' -> Unit

                    // Characters XML 1.0 can't carry at all are dropped.
                    c.code < 0x20 || c == '￾' || c == '￿' -> Unit

                    else -> b.append(c)
                }
            }
            return b.toString()
        }
    }
}
