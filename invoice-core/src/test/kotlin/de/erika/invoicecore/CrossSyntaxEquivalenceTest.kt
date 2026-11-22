package de.erika.invoicecore

import de.erika.invoicecore.support.ModelDump
import de.erika.invoicecore.support.Suites
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The XRechnung test suite contains each business case in both UBL and CII.
 * Both must map to the same neutral model. Unlike golden files, this check is
 * independent of the parser's own output: a mapping mistake in either syntax
 * shows up as a difference.
 *
 * Two kinds of difference are tolerated, both listed explicitly below:
 * inherent syntax differences, and places where the two test files of a pair
 * simply contain different data. Anything else fails.
 */
class CrossSyntaxEquivalenceTest {
    @TestFactory
    fun `UBL and CII versions of the same invoice map to the same model`(): List<DynamicTest> {
        val files = Suites.files(Suites.xrechnung, "xml")
        val pairs =
            files
                .filter { it.name.endsWith("_ubl.xml") }
                .mapNotNull { ubl -> files.firstOrNull { it.path == ubl.path.replace("_ubl.xml", "_uncefact.xml") }?.let { ubl to it } }
        assertTrue(pairs.size >= 35, "expected the XRechnung UBL/CII pairs, found ${pairs.size}")
        return pairs.map { (ubl, cii) ->
            val case = Suites.relative(ubl).removeSuffix("_ubl.xml").substringAfter("instances/")
            dynamicTest(case) {
                val ignored = DATA_DIFFERENCES.filterKeys { case.endsWith(it) }.values.flatten()
                val ublDump = normalise(ModelDump.render(InvoiceParser.parse(ubl.readBytes()).invoice), ignored)
                val ciiDump = normalise(ModelDump.render(InvoiceParser.parse(cii.readBytes()).invoice), ignored)
                assertEquals(ublDump, ciiDump)
            }
        }
    }

    private fun normalise(
        dump: String,
        ignoredPrefixes: List<String>,
    ): String {
        var lines =
            dump
                .lines()
                .filterNot { line -> ignoredPrefixes.any { line.startsWith(it) } }
                // Optional totals: CII test files write explicit zeros where UBL omits the element.
                .filterNot { it in ZERO_TOTALS }
                // CII D16B allows only one preceding invoice reference (BG-3); UBL allows several.
                .filterNot { PRECEDING_BEYOND_FIRST.containsMatchIn(it) }

        // UBL needs cac:OrderReference/cbc:ID even if only BT-14 exists; the test files use a placeholder.
        if (lines.any { it.startsWith("salesOrderReference = ") }) lines = lines.filterNot { it == "purchaseOrderReference = Dummywert" }

        // BT-29: CII lists ram:ID before ram:GlobalID, UBL keeps document order, so compare unordered.
        return sortGroups(sortGroups(lines, "seller.identifiers["), "lines[0].item.attributes[").joinToString("\n")
    }

    private fun sortGroups(
        lines: List<String>,
        prefix: String,
    ): List<String> {
        val (group, rest) = lines.partition { it.startsWith(prefix) }
        // Group by the index right after the prefix, e.g. "lines[0].item.attributes[2]".
        val entries =
            group
                .groupBy { it.removePrefix(prefix).substringBefore(']') }
                .values
                .map { g -> g.joinToString("|") { it.removePrefix(prefix).substringAfter("].") } }
                .sorted()
        return rest + entries.map { "$prefix*] $it" }
    }

    private companion object {
        val ZERO_TOTALS =
            setOf(
                "totals.allowanceTotal = 0",
                "totals.chargeTotal = 0",
                "totals.paidAmount = 0",
                "totals.roundingAmount = 0",
                "totals.totalVat = 0",
            )
        val PRECEDING_BEYOND_FIRST = Regex("""^precedingInvoices\[[1-9]\d*]""")

        private val COMPREHENSIVE =
            listOf(
                // UBL file puts the trading name into RegistrationName and vice versa.
                "buyer.name",
                "buyer.tradingName",
                // Only the CII file contains BT-61 and the extra BT-29 identifiers.
                "payee.legalRegistrationId",
                "seller.identifiers",
                // UBL file has a contact person, CII file a department.
                "seller.contact.point",
                // Only the UBL file has a base quantity on line 2.
                "lines[1].price.baseQuantity",
            )

        /** Places where the UBL and CII test files of a pair contain different data. */
        val DATA_DIFFERENCES: Map<String, List<String>> =
            mapOf(
                "standard/01.20a-INVOICE" to listOf("seller.address.line1", "seller.address.city"),
                "standard/01.21a-INVOICE" to listOf("seller.vatId", "paymentInstructions.meansText", "vatBreakdown[0].exemptionReason"),
                "standard/03.01a-INVOICE" to listOf("buyer.tradingName"),
                // UBL file has city and postcode swapped.
                "standard/03.06a-INVOICE" to
                    listOf("seller.address.city", "seller.address.postCode", "buyer.address.city", "buyer.address.postCode"),
                "standard/03.07a-INVOICE" to listOf("payee.legalRegistrationId", "seller.identifiers"),
                "cius/01.01_comprehensive_test" to COMPREHENSIVE,
                "cius/01.02_comprehensive_test" to COMPREHENSIVE,
                "cius/01.03_comprehensive_test" to COMPREHENSIVE,
                "cius/01.04_comprehensive_test" to COMPREHENSIVE,
                // Typo in the CII file's item description; only the CII file has BT-61.
                "cvd/02.01a-cvd_INVOICE" to listOf("lines[0].item.description", "payee.legalRegistrationId", "seller.identifiers"),
            )
    }
}
