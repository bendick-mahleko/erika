package de.erika.reports

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.HexFormat

/**
 * The canonical form of a report and its SHA-256 (M-MO-09, M-BR-01): what the customer sees and
 * signs. The app builds the same input from its records; both must give exactly the same text
 * (vectors `mara-rules/vectors/report/canonical.json`, Dart `report_canonical.dart`).
 */
object ReportCanonical {
    /** Format version, part of the canonical text. */
    const val FORMAT = 1

    private val DECIMALS = setOf("quantity", "length", "width", "height", "count", "result")
    private val INSTANTS = setOf("start", "end", "signedAt")
    private val UTC_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun canonical(raw: Map<String, Any?>): String = StringBuilder().also { write(normalize(raw, null)!!, it) }.toString()

    fun sha256(canonical: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))

    fun hash(raw: Map<String, Any?>): String = sha256(canonical(raw))

    private fun trim(s: String): String = s.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    private fun normalize(
        value: Any?,
        key: String?,
    ): Any? =
        when (value) {
            null -> {
                null
            }

            is Map<*, *> -> {
                value.entries
                    .mapNotNull { (k, v) -> normalize(v, k as String)?.let { k to it } }
                    .toMap()
            }

            is List<*> -> {
                val items = value.mapNotNull { normalize(it, null) }

                @Suppress("UNCHECKED_CAST")
                val maps = items.filterIsInstance<Map<String, Any?>>()
                val sortKey =
                    maps.firstOrNull()?.let {
                        if ("id" in it) {
                            "id"
                        } else if ("key" in it) {
                            "key"
                        } else {
                            null
                        }
                    }
                if (sortKey != null && maps.size == items.size) items.sortedBy { (it as Map<*, *>)[sortKey] as String? ?: "" } else items
            }

            is Boolean, is Int, is Long -> {
                value
            }

            is BigDecimal -> {
                decimal(value.toPlainString())
            }

            is String -> {
                val s = trim(value)
                when {
                    s.isEmpty() -> null
                    key in DECIMALS -> decimal(s)
                    key in INSTANTS -> instant(s)
                    else -> s
                }
            }

            else -> {
                error("unexpected ${value::class.simpleName} in a report")
            }
        }

    private fun decimal(s: String): String {
        val d = BigDecimal(s)
        if (d.signum() == 0) return "0"
        return d.stripTrailingZeros().toPlainString()
    }

    private fun instant(s: String): String =
        OffsetDateTime
            .parse(s.replace('z', 'Z'))
            .withOffsetSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(UTC_SECONDS)

    private fun write(
        value: Any,
        out: StringBuilder,
    ) {
        when (value) {
            is Map<*, *> -> {
                out.append('{')
                value.keys
                    .map { it as String }
                    .sorted()
                    .forEachIndexed { i, k ->
                        if (i > 0) out.append(',')
                        string(k, out)
                        out.append(':')
                        write(value[k]!!, out)
                    }
                out.append('}')
            }

            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v!!, out)
                }
                out.append(']')
            }

            is Boolean, is Int, is Long -> {
                out.append(value.toString())
            }

            is String -> {
                string(value, out)
            }

            else -> {
                error("unexpected value")
            }
        }
    }

    private fun string(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch.code < 0x20 -> out.append("\\u").append("%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
