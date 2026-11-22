package de.erika.invoicecore.check

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleTextsTest {
    @Test
    fun `every rule ID has a German and an English text (VW-04)`() {
        // Rule IDs are `const val`s, i.e. static String fields on the check objects.
        val declared =
            listOf(ArithmeticChecks::class.java, MandatoryContentChecks::class.java)
                .flatMap { it.declaredFields.toList() }
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { it.get(null) as String }
                .filter { it.startsWith("ERIKA-") }
                .toSet()
        assertEquals(27, declared.size, "13 arithmetic + 14 § 14 UStG rules")
        assertEquals(declared, RuleTexts.ruleIds)
        declared.forEach { id ->
            val f = Finding(id, Severity.ERROR, emptyList())
            assertFalse(f.message("de") == id, "German text for $id")
            assertFalse(f.message("en") == id, "English text for $id")
        }
    }

    @Test
    fun `placeholders and line ID are filled in`() {
        val f =
            Finding(
                ArithmeticChecks.LINE_NET_AMOUNT,
                Severity.WARNING,
                listOf("BT-131"),
                lineId = "3",
                params = mapOf("expected" to "10.00", "actual" to "11.00"),
            )
        assertEquals("Der Positionsbetrag (11.00) entspricht nicht Menge mal Preis (10.00). (Position 3)", f.message())
        assertTrue(f.message("en").endsWith("(line 3)"))
        assertEquals("UNKNOWN-RULE", Finding("UNKNOWN-RULE", Severity.ERROR, emptyList()).message())
    }
}
