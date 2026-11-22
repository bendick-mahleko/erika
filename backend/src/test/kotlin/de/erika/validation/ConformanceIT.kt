package de.erika.validation

import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VW-03 acceptance: for every file of the official XRechnung test suite, ERIKA's result
 * equals the reference KoSIT validator result.
 *
 * Reference = KoSIT validator 1.6.3 (the reference implementation) run with the official
 * XRechnung configuration exactly as published. ERIKA = the full pipeline (format
 * detection, official + ERIKA scenarios, custom levels, message mapping, content checks).
 * Compared per file: accept/reject, and the set of (rule, level) messages.
 */
class ConformanceIT : WorkerIntegrationTest() {
    @Autowired lateinit var validator: InvoiceValidator

    @Autowired lateinit var kosit: KositEngine

    @Autowired lateinit var packs: ValidationPacks

    @TestFactory
    fun `every official XRechnung test file - ERIKA equals the KoSIT reference`(): List<DynamicTest> {
        val suite = File(System.getProperty("erika.test-fixtures"), "xrechnung/instances")
        val files =
            suite
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .sortedBy { it.path }
                .toList()
        assertTrue(files.size >= 80, "expected the full test suite, found ${files.size} files")
        val pack = packs.activeOn()

        return files.map { file ->
            DynamicTest.dynamicTest(file.relativeTo(suite).path) {
                val bytes = file.readBytes()
                val reference = kosit.validateOfficial(pack, bytes, file.name)
                val erika = validator.validate(bytes)

                assertTrue(
                    reference.accepted,
                    "the reference accepts every file of the official suite: ${reference.messages.filter { it.level == "error" }}",
                )
                assertEquals(reference.scenario, erika.scenario, "same scenario")
                assertEquals(reference.accepted, erika.kositAccepted, "same accept/reject recommendation")
                assertEquals(
                    reference.messages.map { it.code to it.level }.toSet(),
                    erika.messages
                        .filter { it.source == MessageSource.SCHEMATRON || it.source == MessageSource.XML_SCHEMA }
                        .map { (if (it.ruleId == "XSD") it.originalText!!.substringBefore(':') else it.ruleId) to levelName(it.severity) }
                        .toSet(),
                    "same rule messages",
                )
                // ERIKA's own content checks must not contradict the reference on valid files.
                assertTrue(erika.errors.isEmpty(), "ERIKA reports errors on a file the reference accepts: ${erika.errors}")
            }
        }
    }

    private fun levelName(severity: MessageSeverity) =
        when (severity) {
            MessageSeverity.ERROR -> "error"
            MessageSeverity.WARNING -> "warning"
            MessageSeverity.INFO -> "information"
        }
}
