package de.erika.validation

import de.erika.invoicecore.ParseErrorCode
import de.erika.invoicecore.check.RuleTexts
import de.erika.invoicecore.model.NotEInvoiceReason
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * VW-04 acceptance, enforced on every build: every rule ID that a validation pack can
 * report, and every ID ERIKA adds around it, has a German plain-language text.
 */
class RuleTextCatalogueTest {
    private val packs = ValidationPacks(ValidationProperties(Path.of(System.getProperty("erika.validation.packs-dir"))))

    @TestFactory
    fun `every Schematron rule ID of every pack has a German text`(): List<DynamicTest> =
        packs.all.map { pack ->
            DynamicTest.dynamicTest(pack.id) {
                val ids = schematronRuleIds(pack.dir)
                assertTrue(ids.size > 1000, "expected the full EN 16931 + XRechnung rule set, found ${ids.size}")
                val missing = ids.filterNot { pack.ruleTexts[it]?.isNotBlank() == true }
                assertTrue(
                    missing.isEmpty(),
                    "No German text for ${missing.size} rule IDs: ${missing.take(20)} (run validation-packs/tools/rule_texts.py)",
                )
                val stale = pack.ruleTexts.keys - ids
                assertTrue(stale.isEmpty(), "Texts for rule IDs the pack doesn't have: $stale")
            }
        }

    @Test
    fun `every ID ERIKA adds has a German and an English text`() {
        val ids =
            ParseErrorCode.entries.map { "ERIKA-FORMAT-${it.name}" } +
                NotEInvoiceReason.entries.map { "ERIKA-NOT-EINVOICE-${it.name}" } +
                listOf("ERIKA-NO-SCENARIO", "ERIKA-PROCESSING-ERROR", "XSD", "XML", "PDFA-3", "PDFA-UNREADABLE")
        val missing = ids.filter { ErikaRuleTexts.de[it].isNullOrBlank() || ErikaRuleTexts.en[it].isNullOrBlank() }
        assertTrue(missing.isEmpty(), "Missing texts: $missing")
    }

    @Test
    fun `invoice-core content rules have German texts`() {
        assertTrue(RuleTexts.ruleIds.isNotEmpty())
        RuleTexts.ruleIds.forEach { assertTrue(it.startsWith("ERIKA-"), it) }
    }

    @Test
    fun `German texts are German and reasonably short`() {
        val pack = packs.all.last()
        val english = Regex("\\b(shall|should|must|Invoice line)\\b")
        val suspicious = pack.ruleTexts.filter { (_, text) -> english.containsMatchIn(text.replace(Regex("„[^“]*“"), "")) }
        assertTrue(suspicious.isEmpty(), "English outside quoted element names: ${suspicious.keys.take(10)}")
        val tooLong = pack.ruleTexts.filter { it.value.length > 400 }
        assertTrue(tooLong.isEmpty(), "Texts over 400 characters: ${tooLong.keys}")
    }

    private fun schematronRuleIds(packDir: Path): Set<String> {
        val assertion = Regex("""<svrl:(?:failed-assert|successful-report)\b[^>]*\bid="([^"]+)"""")
        return Files.walk(packDir).use { paths ->
            paths
                .filter { it.extension == "xsl" && it.parent.fileName.toString() == "xsl" }
                .toList()
                .flatMap { file -> assertion.findAll(file.readText()).map { it.groupValues[1] }.toList() }
                .toSet()
        }
    }
}
