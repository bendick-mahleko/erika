package de.erika.invoicecore

import de.erika.invoicecore.model.Bt
import de.erika.invoicecore.model.Invoice
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals

/**
 * VW-01 needs every EN 16931 business term that can occur in a file to be shown.
 * This proves the neutral model has a place for each of them: every BT and BG of
 * EN 16931-1 must be carried by some model property.
 */
class ModelCompletenessTest {
    @Test
    fun `every EN 16931 business term and group has a property in the model`() {
        val covered = collect(Invoice::class)
        val missingTerms = EN16931_TERMS - covered
        val missingGroups = EN16931_GROUPS - covered
        assertEquals(emptySet(), missingTerms, "business terms without a model property")
        assertEquals(emptySet(), missingGroups, "business groups without a model property")
    }

    private fun collect(
        kClass: KClass<*>,
        seen: MutableSet<KClass<*>> = mutableSetOf(),
    ): Set<String> {
        if (!seen.add(kClass) || !kClass.qualifiedName!!.startsWith("de.erika.invoicecore.model")) return emptySet()
        return kClass.memberProperties
            .flatMap { prop ->
                prop
                    .findAnnotation<Bt>()
                    ?.ids
                    .orEmpty()
                    .toList() + nested(prop.returnType).flatMap { collect(it, seen) }
            }.toSet()
    }

    private fun nested(type: KType): List<KClass<*>> =
        listOfNotNull(type.classifier as? KClass<*>) + type.arguments.mapNotNull { it.type?.classifier as? KClass<*> }

    private companion object {
        /** EN 16931-1:2017 numbers business terms BT-1 to BT-165; BT-4 is not used. */
        val EN16931_TERMS = ((1..165).toSet() - 4).map { "BT-$it" }.toSet()
        val EN16931_GROUPS = (1..32).map { "BG-$it" }.toSet()
    }
}
