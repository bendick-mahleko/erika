package de.erika.reportinvoicing

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/** Invoice lines from reports against `mara-rules/vectors/invoicing/lines.json` (M-BR-04, M-BR-05). */
class LineVectorsTest {
    private val mapper = JsonMapper.builder().build()

    private fun JsonNode.decimal(field: String): BigDecimal? = path(field).takeIf { it.isString }?.let { BigDecimal(it.asString()) }

    private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

    @TestFactory
    fun `invoice lines match the shared vectors`(): List<DynamicTest> {
        val file = mapper.readTree(File("../mara-rules/vectors/invoicing/lines.json"))
        assertEquals("invoicing.lines", file.path("rule").asString())
        return file.path("cases").values().map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                val i = case.path("input")
                val rules =
                    BillingRules(
                        billingUnitMinutes = i.path("billingUnitMinutes").asInt(),
                        defaultHourlyRate = i.decimal("defaultHourlyRate"),
                        travelMode = TravelMode.valueOf(i.path("travelMode").asString()),
                        travelFlatAmount = i.decimal("travelFlatAmount"),
                        travelHourlyRate = i.decimal("travelHourlyRate"),
                    )
                val reports =
                    i.path("reports").values().map { r ->
                        BillableReport(
                            r.path("id").asString(),
                            r.path("times").values().map { t ->
                                BillableTime(
                                    t.path("kind").asString(),
                                    OffsetDateTime.parse(t.path("start").asString()),
                                    t.path("end").takeIf { it.isString }?.let { OffsetDateTime.parse(it.asString()) },
                                    t.decimal("rate"),
                                )
                            },
                            r.path("materials").values().map { m ->
                                BillableMaterial(
                                    m.path("description").asString(),
                                    m.decimal("quantity")!!,
                                    m.path("unitCode").asString(),
                                    m.decimal("netPrice"),
                                )
                            },
                        )
                    }
                val lines = ReportLineRules.lines(rules, reports)
                val actual =
                    lines.map { l ->
                        buildMap {
                            put("report", l.report)
                            put("kind", l.kind.name)
                            l.minutes?.let { put("minutes", it) }
                            put("quantity", l.quantity.plain())
                            put("unitCode", l.unitCode)
                            put("netPrice", if (l.kind == LineKind.MATERIAL) l.netPrice?.plain() else l.netPrice?.toPlainString())
                            put("net", l.net.toPlainString())
                            put("priceMissing", l.priceMissing)
                            put("laborCost", l.laborCost)
                        }
                    }
                val expected =
                    case.path("expected").path("lines").values().map { e ->
                        buildMap {
                            put("report", e.path("report").asString())
                            put("kind", e.path("kind").asString())
                            if (e.has("minutes")) put("minutes", e.path("minutes").asInt())
                            put("quantity", e.path("quantity").asString())
                            put("unitCode", e.path("unitCode").asString())
                            put("netPrice", e.path("netPrice").takeIf { it.isString }?.asString())
                            put("net", e.path("net").asString())
                            put("priceMissing", e.path("priceMissing").asBoolean())
                            put("laborCost", e.path("laborCost").asBoolean())
                        }
                    }
                assertEquals(expected, actual)
                assertEquals(case.path("expected").path("totalNet").asString(), ReportLineRules.total(lines).toPlainString())
            }
        }
    }
}
