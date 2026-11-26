package de.erika.reports

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.test.assertEquals

/** The shared vectors of the canonical report (`mara-rules/vectors/report/canonical.json`); the app reads the same file. */
class ReportCanonicalVectorsTest {
    @TestFactory
    fun `the canonical report and its hash match the shared vectors`(): List<DynamicTest> {
        val mapper = JsonMapper.builder().build()
        val file = mapper.readTree(File("../mara-rules/vectors/report/canonical.json"))
        assertEquals("report.canonical", file.path("rule").asString())
        return file.path("cases").values().map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                @Suppress("UNCHECKED_CAST")
                val input = mapper.convertValue(case.path("input"), Map::class.java) as Map<String, Any?>
                val canonical = ReportCanonical.canonical(input)
                assertEquals(case.path("expected").path("canonical").asString(), canonical)
                assertEquals(case.path("expected").path("sha256").asString(), ReportCanonical.sha256(canonical))
            }
        }
    }
}
