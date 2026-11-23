package de.erika.creator

import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.tenancy.TenantContext
import de.erika.support.TestDatabase
import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * CR-03 exit criterion: 100 parallel issue requests produce 100 unique, gap-free numbers.
 * Each issue runs in its own transaction on its own connection, as parallel workers would,
 * and contends for the same `number_sequence` row (SELECT … FOR UPDATE).
 */
class IssueConcurrencyIT : WorkerIntegrationTest() {
    @Autowired lateinit var issuer: InvoiceIssuer

    @Autowired lateinit var tenant: TenantContext

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var json: JsonMapper

    @Test
    fun `100 parallel issues give 100 unique numbers without gaps`() {
        val ws = UUID.randomUUID()
        TestDatabase.update(
            """
            INSERT INTO workspace (id, name, street, postal_code, city, country_code, vat_id, tax_number)
            VALUES (?, 'Parallel GmbH', 'Musterstraße 1', '10115', 'Berlin', 'DE', 'DE123456789', '30/123/45678')
            """.trimIndent(),
            ws,
        )
        TestDatabase.update(
            """
            INSERT INTO invoice_settings (workspace_id, contact_name, contact_phone, contact_email, iban, number_pattern)
            VALUES (?, 'Erika Mustermann', '+49 30 1234567', 'rechnung@parallel.example', 'DE02120300000000202051', 'P-{NNNN}')
            """.trimIndent(),
            ws,
        )
        val content =
            DraftContent(
                format = OutputFormat.XRECHNUNG_CII,
                buyer =
                    BuyerSnapshot(
                        name = "Kunde AG",
                        street = "Kundenweg 2",
                        postalCode = "80331",
                        city = "München",
                        countryCode = "DE",
                        email = "eingang@kunde.example",
                    ),
                vatTreatment = VatTreatment.DOMESTIC,
                deliveryDate = LocalDate.now().minusDays(1),
                buyerReference = "K-1",
                lines =
                    listOf(
                        DraftLine(
                            name = "Leistung",
                            quantity = BigDecimal.ONE,
                            unitCode = "C62",
                            netPrice = BigDecimal("10.00"),
                            vatRate = LineVatRate.STANDARD_19,
                        ),
                    ),
            )
        val ids = (1..100).map { UUID.randomUUID() }
        ids.forEach {
            TestDatabase.update(
                "INSERT INTO invoice_draft (id, workspace_id, state, data) VALUES (?, ?, 'ISSUING', CAST(? AS jsonb))",
                it,
                ws,
                json.writeValueAsString(content),
            )
        }

        val pool = Executors.newFixedThreadPool(16)
        val numbers =
            try {
                pool
                    .invokeAll(
                        ids.map { id ->
                            Callable {
                                tx.execute {
                                    tenant.enterWorkspace(ws)
                                    issuer.issue(id, null, finalAttempt = true)
                                }
                            }
                        },
                        10,
                        TimeUnit.MINUTES,
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        assertEquals(100, numbers.filterNotNull().size, "every draft issued: $numbers")
        assertEquals(100, numbers.toSet().size, "numbers are unique")
        assertEquals((1..100).map { "P-%04d".format(it) }, numbers.filterNotNull().sorted(), "numbers are gap-free")
        val stored =
            TestDatabase.asOwner { c ->
                c
                    .prepareStatement(
                        "SELECT count(DISTINCT number), max(next_value) FROM invoice i JOIN number_sequence s USING (workspace_id) WHERE workspace_id = ?",
                    ).apply { setObject(1, ws) }
                    .executeQuery()
                    .run {
                        next()
                        getInt(1) to getLong(2)
                    }
            }
        assertEquals(100 to 101L, stored)
    }
}
