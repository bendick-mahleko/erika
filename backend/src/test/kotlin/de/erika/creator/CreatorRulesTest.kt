package de.erika.creator

import de.erika.identity.WorkspaceProfile
import de.erika.invoicecore.write.InvoiceWriter
import de.erika.invoicecore.write.OutputFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatorRulesTest {
    private val seller =
        WorkspaceProfile(UUID.randomUUID(), "Muster GmbH", "Musterstraße 1", "10115", "Berlin", "DE", "DE123456789", "30/123/45678")
    private val settings =
        InvoiceSettings(contactName = "E. M.", contactPhone = "+49 30 1", contactEmail = "r@m.example", iban = "DE02120300000000202051")
    private val day = LocalDate.of(2026, 11, 23)

    private fun content(
        treatment: VatTreatment = VatTreatment.DOMESTIC,
        rate: LineVatRate? = LineVatRate.STANDARD_19,
        vatId: String? = null,
        country: String = "DE",
        format: OutputFormat = OutputFormat.ZUGFERD_EN16931,
    ) = DraftContent(
        format = format,
        buyer =
            BuyerSnapshot(
                name = "Kunde",
                street = "Weg 1",
                postalCode = "80331",
                city = "Ort",
                countryCode = country,
                vatId = vatId,
                email = "k@k.example",
            ),
        vatTreatment = treatment,
        deliveryDate = day,
        buyerReference = "R-1",
        lines =
            listOf(
                DraftLine(name = "Leistung", quantity = BigDecimal("2"), unitCode = "HUR", netPrice = BigDecimal("50.00"), vatRate = rate),
            ),
    )

    /** CR-04 / BR-06, one row per VAT case: category code, rate, VAT amount and note text. */
    @ParameterizedTest(name = "{0} / {1} / Kleinunternehmer={2} → {3} {4} %")
    @CsvSource(
        "DOMESTIC,       STANDARD_19, false, S,  19, 19.00, ",
        "DOMESTIC,       REDUCED_7,   false, S,  7,  7.00,  ",
        "DOMESTIC,       ZERO,        false, Z,  0,  0.00,  ",
        "REVERSE_CHARGE, STANDARD_19, false, AE, 0,  0.00,  § 13b UStG",
        "INTRA_EU,       STANDARD_19, false, K,  0,  0.00,  § 6a UStG",
        "DOMESTIC,       STANDARD_19, true,  E,  0,  0.00,  § 19 UStG",
    )
    fun `each VAT case gets the right category code, VAT and note`(
        treatment: VatTreatment,
        rate: LineVatRate,
        kleinunternehmer: Boolean,
        category: String,
        percent: BigDecimal,
        vat: BigDecimal,
        note: String?,
    ) {
        val s = settings.copy(kleinunternehmer = kleinunternehmer)
        val foreign = treatment != VatTreatment.DOMESTIC
        val c = content(treatment, rate, vatId = if (foreign) "FR12345678901" else null, country = if (foreign) "FR" else "DE")
        assertEquals(emptyList(), DraftChecks.problems(InvoiceKind.INVOICE, c, s, seller))
        val invoice =
            InvoiceWriter.prepare(
                InvoiceAssembler.build(InvoiceKind.INVOICE, c, s, seller, "RE-1", day, null),
                OutputFormat.XRECHNUNG_CII,
            )
        val breakdown = invoice.vatBreakdown.single()
        assertEquals(category, breakdown.categoryCode)
        assertEquals(0, percent.compareTo(breakdown.rate))
        assertEquals(0, vat.compareTo(breakdown.taxAmount))
        if (note == null) {
            assertTrue(invoice.notes.isEmpty())
            assertNull(breakdown.exemptionReason)
        } else {
            assertTrue(
                invoice.notes
                    .single()
                    .text
                    .contains(note),
                invoice.notes.toString(),
            )
            assertNotNull(breakdown.exemptionReason)
        }
        if (treatment == VatTreatment.INTRA_EU) assertEquals("FR", invoice.delivery?.deliverToAddress?.countryCode, "BR-IC-12")
    }

    @Test
    fun `reverse charge and intra-EU need VAT IDs, Kleinunternehmer can't use them`() {
        assertEquals(
            listOf(Problem("buyer.vatId", "VAT_ID_REQUIRED")),
            DraftChecks.problems(InvoiceKind.INVOICE, content(VatTreatment.REVERSE_CHARGE), settings, seller),
        )
        val intra = DraftChecks.problems(InvoiceKind.INVOICE, content(VatTreatment.INTRA_EU), settings, seller.copy(vatId = null))
        assertEquals(setOf("buyer.vatId", "seller.vatId", "buyer.countryCode"), intra.map { it.field }.toSet())
        assertEquals(
            listOf(Problem("vatTreatment", "NOT_FOR_KLEINUNTERNEHMER")),
            DraftChecks.problems(
                InvoiceKind.INVOICE,
                content(VatTreatment.REVERSE_CHARGE, vatId = "FR1"),
                settings.copy(kleinunternehmer = true),
                seller,
            ),
        )
    }

    @Test
    fun `XRechnung needs the seller contact, buyer reference and buyer email`() {
        val c = content(format = OutputFormat.XRECHNUNG_UBL).let { it.copy(buyerReference = null, buyer = it.buyer.copy(email = null)) }
        val problems = DraftChecks.problems(InvoiceKind.INVOICE, c, InvoiceSettings(iban = "DE1"), seller).map { it.field }
        assertEquals(
            setOf("buyerReference", "buyer.email", "settings.contactName", "settings.contactPhone", "settings.contactEmail"),
            problems.toSet(),
        )
    }

    @Test
    fun `dates and lines are checked`() {
        val c =
            content().copy(
                deliveryDate = null,
                periodStart = day,
                periodEnd = day.minusDays(1),
                issueDate = day,
                dueDate = day.minusDays(1),
            )
        val bad = c.copy(lines = listOf(DraftLine(name = " ", quantity = BigDecimal.ZERO, unitCode = "hour", netPrice = BigDecimal("-1"))))
        val fields = DraftChecks.problems(InvoiceKind.INVOICE, bad, settings, seller).map { "${it.field}:${it.code}" }
        assertEquals(
            listOf(
                "periodEnd:PERIOD_ORDER",
                "dueDate:BEFORE_ISSUE_DATE",
                "lines[0].name:REQUIRED",
                "lines[0].quantity:INVALID",
                "lines[0].unitCode:INVALID",
                "lines[0].netPrice:INVALID",
                "lines[0].vatRate:REQUIRED",
            ),
            fields,
        )
        assertEquals(
            listOf(
                "lines:REQUIRED",
            ),
            DraftChecks.problems(InvoiceKind.INVOICE, content().copy(lines = emptyList()), settings, seller).map {
                "${it.field}:${it.code}"
            },
        )
    }

    @Test
    fun `seller identification without VAT ID uses the tax number (BR-CO-26)`() {
        val noVat = seller.copy(vatId = null)
        val invoice = InvoiceAssembler.build(InvoiceKind.INVOICE, content(), settings, noVat, "RE-1", day, null)
        assertEquals(
            "30/123/45678",
            invoice.seller.identifiers
                .single()
                .value,
        )
        assertEquals("Zahlbar ohne Abzug bis zum 07.12.2026.", invoice.paymentTerms)
        assertEquals(day.plusDays(14), invoice.dueDate)
        val credit =
            InvoiceAssembler.build(
                InvoiceKind.CANCELLATION,
                content(),
                settings,
                seller,
                "GS-1",
                day,
                OriginalReference("RE-1", day),
            )
        assertEquals("381", credit.typeCode)
        assertNull(credit.dueDate)
        assertEquals("RE-1", credit.precedingInvoices.single().number)
    }

    @ParameterizedTest
    @CsvSource(
        "RE-{YYYY}-{NNNN}, RE-2026-0042, RE-2026-{N}",
        "{YY}{MM}/{NNN},   2611/042,     2611/{N}",
        "{N},              42,           {N}",
        "GS_{NNNNNN},      GS_000042,    GS_{N}",
    )
    fun `number patterns`(
        pattern: String,
        number: String,
        key: String,
    ) {
        val p = NumberPattern.parse(pattern)!!
        assertEquals(number, p.format(day, 42))
        assertEquals(key, p.sequenceKey(day))
    }

    @ParameterizedTest
    @CsvSource("RE-{YYYY}", "{NN}-{NNN}", "RE {NNNN}", "{DD}-{NNNN}", "RE-{NNNN}-ÄÖ", "''")
    fun `invalid number patterns are refused`(pattern: String) {
        assertNull(NumberPattern.parse(pattern))
    }
}
