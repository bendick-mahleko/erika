package de.erika.sync

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.test.assertEquals

/** The shared sync vectors in `mara-rules/` (ADR 0038); the app's Dart tests read the same file. */
class SyncVectorsTest {
    private val vectors: JsonNode = JsonMapper.builder().build().readTree(File("../mara-rules/vectors/sync/field-merge.json"))

    @TestFactory
    fun `field merge matches the shared vectors`(): List<DynamicTest> {
        assertEquals("sync.field-merge", vectors.path("rule").asString())
        return vectors.path("cases").values().map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                val input = case.path("input")
                val decision =
                    ConflictRules.decide(
                        policy = FieldPolicy.valueOf(input.path("policy").asString()),
                        baseVersion = input.path("baseVersion").asLong(),
                        serverVersion = input.path("serverVersion").asLong(),
                        sameValue = input.path("sameValue").asBoolean(),
                    )
                assertEquals(case.path("expected").path("result").asString(), decision.name)
            }
        }
    }
}
