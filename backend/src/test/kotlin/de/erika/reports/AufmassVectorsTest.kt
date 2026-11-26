package de.erika.reports

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shared Aufmaß and ID vectors in `mara-rules/` (ADR 0036). The app's Dart tests read the
 * same files, so both sides calculate identically.
 */
class AufmassVectorsTest {
    private val mapper = JsonMapper.builder().build()

    private fun vectors(
        path: String,
        rule: String,
    ): List<JsonNode> {
        val file = mapper.readTree(File("../mara-rules/vectors/$path"))
        assertEquals(rule, file.path("rule").asString())
        return file.path("cases").values().toList()
    }

    /** Runs [calculate]; compares its result or its error code with the case. */
    private fun cases(
        path: String,
        rule: String,
        calculate: (JsonNode) -> String,
    ): List<DynamicTest> =
        vectors(path, rule).map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                val expected = case.path("expected")
                if (expected.has("error")) {
                    val e = assertFailsWith<AufmassException> { calculate(case.path("input")) }
                    assertEquals(expected.path("error").asString(), e.code)
                } else {
                    assertEquals(expected.path("result").asString(), calculate(case.path("input")))
                }
            }
        }

    private fun JsonNode.decimal(field: String): BigDecimal? = path(field).takeIf { it.isString }?.let { BigDecimal(it.asString()) }

    @TestFactory
    fun `area matches the shared vectors`() =
        cases("aufmass/area.json", "aufmass.area") {
            AufmassRules.area(it.decimal("length")!!, it.decimal("width")!!).toPlainString()
        }

    @TestFactory
    fun `formulas match the shared vectors`() =
        cases("aufmass/formula.json", "aufmass.formula") { AufmassRules.formula(it.path("expression").asString()).toPlainString() }

    @TestFactory
    fun `positions match the shared vectors`() =
        cases("aufmass/position.json", "aufmass.position") { input ->
            AufmassRules
                .position(
                    AufmassInput(
                        kind = input.path("kind").asString(),
                        length = input.decimal("length"),
                        width = input.decimal("width"),
                        height = input.decimal("height"),
                        count = input.decimal("count"),
                        expression = input.path("expression").takeIf { it.isString }?.asString(),
                        deductions =
                            input.path("deductions").values().map {
                                AufmassDeductionInput(it.decimal("length"), it.decimal("width"), it.decimal("height"), it.decimal("count"))
                            },
                    ),
                ).toPlainString()
        }

    @TestFactory
    fun `derived IDs match the shared vectors`() =
        vectors("ids/derived.json", "ids.derived").map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                val input = case.path("input")
                val id = DerivedIds.of(UUID.fromString(input.path("namespace").asString()), input.path("name").asString())
                assertEquals(case.path("expected").path("result").asString(), id.toString())
            }
        }
}
