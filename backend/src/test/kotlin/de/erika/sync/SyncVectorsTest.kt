package de.erika.sync

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.test.assertEquals

/** The shared sync vectors in `mara-rules/` (ADR 0038); the app's Dart tests read the same files. */
class SyncVectorsTest {
    private val mapper = JsonMapper.builder().build()

    private fun cases(
        file: String,
        rule: String,
        decide: (JsonNode) -> String,
    ): List<DynamicTest> {
        val vectors = mapper.readTree(File("../mara-rules/vectors/sync/$file"))
        assertEquals(rule, vectors.path("rule").asString())
        return vectors.path("cases").values().map { case ->
            DynamicTest.dynamicTest(case.path("name").asString()) {
                assertEquals(case.path("expected").path("result").asString(), decide(case.path("input")))
            }
        }
    }

    private fun JsonNode.versions(): Map<String, Long> = properties().associate { (k, v) -> k to v.asLong() }

    @TestFactory
    fun `field merge matches the shared vectors`() =
        cases("field-merge.json", "sync.field-merge") { input ->
            ConflictRules
                .decide(
                    policy = FieldPolicy.valueOf(input.path("policy").asString()),
                    baseVersion = input.path("baseVersion").asLong(),
                    serverVersion = input.path("serverVersion").asLong(),
                    sameValue = input.path("sameValue").asBoolean(),
                ).name
        }

    @TestFactory
    fun `deletes match the shared vectors`() =
        cases("delete.json", "sync.delete") { input ->
            ConflictRules.decideDelete(input.path("baseVersions").versions(), input.path("serverVersions").versions()).name
        }

    @TestFactory
    fun `edits of deleted records match the shared vectors`() =
        cases("edit-deleted.json", "sync.edit-deleted") { input ->
            ConflictRules.decideEditOfDeleted(input.path("maxBaseVersion").asLong(), input.path("deletedVersion").asLong()).name
        }
}
