package de.erika.reportinvoicing

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.OffsetDateTime

/** How a firm (or a customer) bills reports (M-BR-04, M-BR-05). */
data class BillingRules(
    val billingUnitMinutes: Int,
    val defaultHourlyRate: BigDecimal?,
    val travelMode: TravelMode,
    val travelFlatAmount: BigDecimal?,
    val travelHourlyRate: BigDecimal?,
)

enum class TravelMode { NONE, FLAT, TIME }

/** A time entry as the rules see it; [rate] is the technician's hourly rate, if any. */
data class BillableTime(
    val kind: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime?,
    val rate: BigDecimal?,
    val technician: String? = null,
)

data class BillableMaterial(
    val description: String,
    val quantity: BigDecimal,
    val unitCode: String,
    val netPrice: BigDecimal?,
    val articleId: java.util.UUID? = null,
    val articleNumber: String? = null,
    val vatRate: String = "STANDARD_19",
)

data class BillableReport<K>(
    val key: K,
    val times: List<BillableTime>,
    val materials: List<BillableMaterial>,
)

enum class LineKind { WORK, TRAVEL, MATERIAL }

data class ProposedLine<K>(
    val report: K,
    val kind: LineKind,
    /** Billed minutes (work and travel by time). */
    val minutes: Int?,
    val quantity: BigDecimal,
    val unitCode: String,
    val netPrice: BigDecimal?,
    val net: BigDecimal,
    val laborCost: Boolean,
    val technicians: List<String> = emptyList(),
    val material: BillableMaterial? = null,
) {
    val priceMissing: Boolean get() = netPrice == null
}

/**
 * Invoice lines from signed reports (vectors `mara-rules/vectors/invoicing/lines.json`): times
 * rounded up per entry to the billing unit (raw times stay unchanged, M-BR-04), summed per report
 * and hourly rate; travel by time, flat per visit or not at all (M-BR-05); materials × price.
 */
object ReportLineRules {
    fun lines(
        rules: BillingRules,
        reports: List<BillableReport<*>>,
    ): List<ProposedLine<Any?>> =
        reports.flatMap { report ->
            buildList {
                addAll(timeLines(rules, report, "WORK", LineKind.WORK))
                if (rules.travelMode == TravelMode.TIME) addAll(timeLines(rules, report, "TRAVEL", LineKind.TRAVEL))
                if (rules.travelMode == TravelMode.FLAT && rules.travelFlatAmount != null) {
                    val price = money(rules.travelFlatAmount)
                    add(ProposedLine(report.key, LineKind.TRAVEL, null, BigDecimal.ONE, "LS", price, price, laborCost = true))
                }
                report.materials.forEach { m ->
                    add(
                        ProposedLine(
                            report.key,
                            LineKind.MATERIAL,
                            null,
                            m.quantity,
                            m.unitCode,
                            m.netPrice,
                            m.netPrice?.let { money(m.quantity.multiply(it)) } ?: ZERO,
                            laborCost = false,
                            material = m,
                        ),
                    )
                }
            }
        }

    /** Minutes billed for one entry: its exact duration rounded up to the billing unit. */
    fun billedMinutes(
        time: BillableTime,
        unitMinutes: Int,
    ): Int {
        val end = time.end ?: return 0
        val seconds = Duration.between(time.start, end).seconds
        if (seconds <= 0) return 0
        val unitSeconds = unitMinutes * 60L
        return (((seconds + unitSeconds - 1) / unitSeconds) * unitMinutes).toInt()
    }

    fun total(lines: List<ProposedLine<*>>): BigDecimal = money(lines.fold(BigDecimal.ZERO) { sum, l -> sum + l.net })

    private fun timeLines(
        rules: BillingRules,
        report: BillableReport<*>,
        kind: String,
        lineKind: LineKind,
    ): List<ProposedLine<Any?>> {
        // Per hourly rate, in the order rates first appear.
        val groups = linkedMapOf<BigDecimal?, Pair<Int, MutableList<String>>>()
        for (t in report.times.filter { it.kind == kind && it.end != null }) {
            var rate = t.rate ?: rules.defaultHourlyRate
            if (lineKind == LineKind.TRAVEL) rate = rules.travelHourlyRate ?: rate
            val key = rate?.stripTrailingZeros()
            val (minutes, people) = groups[key] ?: (0 to mutableListOf())
            t.technician?.let { if (it !in people) people += it }
            groups[key] = (minutes + billedMinutes(t, rules.billingUnitMinutes)) to people
        }
        return groups.mapNotNull { (rate, value) ->
            val (minutes, people) = value
            if (minutes == 0) return@mapNotNull null
            val quantity = BigDecimal(minutes).divide(SIXTY, 4, RoundingMode.HALF_UP)
            val price = rate?.let(::money)
            ProposedLine(
                report.key,
                lineKind,
                minutes,
                quantity,
                "HUR",
                price,
                price?.let { money(quantity.multiply(it)) } ?: ZERO,
                laborCost = true,
                technicians = people,
            )
        }
    }

    private fun money(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    private val SIXTY = BigDecimal(60)
    private val ZERO = BigDecimal("0.00")
}
