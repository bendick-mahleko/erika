package de.erika.reports

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shared Aufmaß vectors in `mara-rules/` (ADR 0036). The app's Dart tests read the same
 * file, so both sides calculate identically.
 */
class AufmassVectorsTest {
    private val vectors: JsonNode = JsonMapper.builder().build().readTree(File("../mara-rules/vectors/aufmass/area.json"))

    @TestFactory
    fun `area matches the shared vectors`(): List<DynamicTest> {
        assertEquals("aufmass.area", vectors.path("rule").asString())
        return vectors.path("cases").values().map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                val length = BigDecimal(case.path("input").path("length").asString())
                val width = BigDecimal(case.path("input").path("width").asString())
                val expected = case.path("expected")
                if (expected.has("error")) {
                    val e = assertFailsWith<AufmassException> { AufmassRules.area(length, width) }
                    assertEquals(expected.path("error").asString(), e.code)
                } else {
                    assertEquals(expected.path("result").asString(), AufmassRules.area(length, width).toPlainString())
                }
            }
        }
    }
}
