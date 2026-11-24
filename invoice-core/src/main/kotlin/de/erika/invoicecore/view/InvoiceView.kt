package de.erika.invoicecore.view

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Contact
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.model.Period
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One labelled value: business term, German label, display text. */
public data class ViewField(
    val bt: String,
    val label: String,
    val value: String,
)

/** A titled group of fields, e.g. the seller (BG-4) or one invoice line (BG-25). */
public data class ViewSection(
    /** Business group ID (`BG-4`), or `HEADER` / `REFERENCES` for the invoice head. */
    val id: String,
    val title: String,
    val fields: List<ViewField>,
    val sections: List<ViewSection> = emptyList(),
)

/** An embedded attachment (BT-125); [index] is its position among the supporting documents (BG-24). */
public data class ViewAttachment(
    val index: Int,
    val fileName: String?,
    val mimeCode: String?,
    val sizeBytes: Int,
)

/**
 * The invoice as a reader sees it (VW-01): every business term present in the file, with a
 * label, grouped as on a paper invoice. In German (default) values are formatted for German
 * readers (dates `02.11.2026`, amounts `1.785,00 EUR`); in English (VW-07) as `2026-11-02` and
 * `1,785.00 EUR`. Codes come with their meaning in the same language.
 */
public data class InvoiceView(
    val title: String,
    val sections: List<ViewSection>,
    val attachments: List<ViewAttachment>,
) {
    /** All fields, depth first (sections, then their subsections). */
    public fun allFields(): List<ViewField> = sections.flatMap { it.allFields() }

    private fun ViewSection.allFields(): List<ViewField> = fields + sections.flatMap { it.allFields() }

    public companion object {
        /** Builds the view of [invoice] in [language] (German by default). */
        public fun of(
            invoice: Invoice,
            language: ViewLanguage = ViewLanguage.DE,
        ): InvoiceView = Builder(invoice, language).build()
    }
}

private class Builder(
    private val invoice: Invoice,
    private val language: ViewLanguage,
) {
    private val en = language == ViewLanguage.EN
    private val currency = invoice.currency
    private val dates =
        if (en) DateTimeFormatter.ISO_LOCAL_DATE else DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
    private val numberLocale = if (en) Locale.UK else Locale.GERMANY

    /** A code list in the view's language. */
    private fun codes(l: Map<String, String>) = CodeLists.of(l, language)

    private fun word(
        de: String,
        en: String,
    ) = if (this.en) en else de

    fun build(): InvoiceView {
        val type = invoice.typeCode?.let { codes(CodeLists.invoiceType)[it.trim()] } ?: word("Rechnung", "Invoice")
        val title = listOfNotNull(type, invoice.number).joinToString(" ")
        val sections =
            listOfNotNull(
                header(),
                references(),
                notes(),
                preceding(),
                seller(),
                buyer(),
                payee(),
                taxRepresentative(),
                delivery(),
                payment(),
                allowancesAndCharges("BG-20", invoice.allowances, allowance = true),
                allowancesAndCharges("BG-21", invoice.charges, allowance = false),
                totals(),
                vatBreakdown(),
                supportingDocuments(),
                lines(),
                thirdPartyPayments(),
            )
        val attachments =
            invoice.supportingDocuments.mapIndexedNotNull { i, doc ->
                doc.attachment?.let { ViewAttachment(i, it.filename, it.mimeCode, it.size) }
            }
        return InvoiceView(title, sections, attachments)
    }

    // --- sections -------------------------------------------------------------------

    private fun header() =
        section(
            "HEADER",
            word("Rechnung", "Invoice"),
            fields {
                text("BT-1", invoice.number)
                date("BT-2", invoice.issueDate)
                code("BT-3", invoice.typeCode, CodeLists.invoiceType)
                text("BT-5", invoice.currency)
                text("BT-6", invoice.vatAccountingCurrency)
                date("BT-7", invoice.taxPointDate)
                code("BT-8", invoice.vatPointDateCode, CodeLists.vatPointDate)
                date("BT-9", invoice.dueDate)
                text("BT-20", invoice.paymentTerms)
                text("BT-23", invoice.businessProcessType)
                text("BT-24", invoice.specificationIdentifier)
            },
        )

    private fun references() =
        section(
            "REFERENCES",
            word("Referenzen", "References"),
            fields {
                text("BT-10", invoice.buyerReference)
                text("BT-11", invoice.projectReference)
                text("BT-12", invoice.contractReference)
                text("BT-13", invoice.purchaseOrderReference)
                text("BT-14", invoice.salesOrderReference)
                text("BT-15", invoice.receivingAdviceReference)
                text("BT-16", invoice.despatchAdviceReference)
                text("BT-17", invoice.tenderOrLotReference)
                id("BT-18", invoice.invoicedObjectIdentifier)
                text("BT-19", invoice.buyerAccountingReference)
            },
        )

    private fun notes() =
        group(
            "BG-1",
            invoice.notes.map { note ->
                section(
                    "BG-1",
                    label("BG-1"),
                    fields {
                        text("BT-21", note.subjectCode)
                        text("BT-22", note.text)
                    },
                )
            },
        )

    private fun preceding() =
        group(
            "BG-3",
            invoice.precedingInvoices.map { ref ->
                section(
                    "BG-3",
                    label("BG-3"),
                    fields {
                        text("BT-25", ref.number)
                        date("BT-26", ref.issueDate)
                    },
                )
            },
        )

    private fun seller() =
        with(invoice.seller) {
            section(
                "BG-4",
                label("BG-4"),
                fields {
                    text("BT-27", name)
                    text("BT-28", tradingName)
                    identifiers.forEach { id("BT-29", it) }
                    id("BT-30", legalRegistrationId)
                    text("BT-31", vatId)
                    text("BT-32", taxRegistrationId)
                    text("BT-33", additionalLegalInformation)
                    id("BT-34", electronicAddress)
                },
                listOfNotNull(
                    address("BG-5", address, listOf("BT-35", "BT-36", "BT-162", "BT-37", "BT-38", "BT-39", "BT-40")),
                    contact("BG-6", contact, listOf("BT-41", "BT-42", "BT-43")),
                ),
            )
        }

    private fun buyer() =
        with(invoice.buyer) {
            section(
                "BG-7",
                label("BG-7"),
                fields {
                    text("BT-44", name)
                    text("BT-45", tradingName)
                    id("BT-46", identifier)
                    id("BT-47", legalRegistrationId)
                    text("BT-48", vatId)
                    id("BT-49", electronicAddress)
                },
                listOfNotNull(
                    address("BG-8", address, listOf("BT-50", "BT-51", "BT-163", "BT-52", "BT-53", "BT-54", "BT-55")),
                    contact("BG-9", contact, listOf("BT-56", "BT-57", "BT-58")),
                ),
            )
        }

    private fun payee() =
        invoice.payee?.let { p ->
            section(
                "BG-10",
                label("BG-10"),
                fields {
                    text("BT-59", p.name)
                    id("BT-60", p.identifier)
                    id("BT-61", p.legalRegistrationId)
                },
            )
        }

    private fun taxRepresentative() =
        invoice.sellerTaxRepresentative?.let { r ->
            section(
                "BG-11",
                label("BG-11"),
                fields {
                    text("BT-62", r.name)
                    text("BT-63", r.vatId)
                },
                listOfNotNull(address("BG-12", r.address, listOf("BT-64", "BT-65", "BT-164", "BT-66", "BT-67", "BT-68", "BT-69"))),
            )
        }

    private fun delivery() =
        invoice.delivery?.let { d ->
            section(
                "BG-13",
                label("BG-13"),
                fields {
                    text("BT-70", d.deliverToName)
                    id("BT-71", d.locationIdentifier)
                    date("BT-72", d.actualDeliveryDate)
                },
                listOfNotNull(
                    period("BG-14", d.invoicingPeriod, "BT-73", "BT-74"),
                    address("BG-15", d.deliverToAddress, listOf("BT-75", "BT-76", "BT-165", "BT-77", "BT-78", "BT-79", "BT-80")),
                ),
            )
        }

    private fun payment() =
        invoice.paymentInstructions?.let { p ->
            section(
                "BG-16",
                label("BG-16"),
                fields {
                    code("BT-81", p.meansTypeCode, CodeLists.paymentMeans)
                    text("BT-82", p.meansText)
                    text("BT-83", p.remittanceInformation)
                },
                p.creditTransfers.map { t ->
                    section(
                        "BG-17",
                        label("BG-17"),
                        fields {
                            text("BT-84", t.accountId)
                            text("BT-85", t.accountName)
                            text("BT-86", t.serviceProviderId)
                        },
                    )
                } +
                    listOfNotNull(
                        p.paymentCard?.let { c ->
                            section(
                                "BG-18",
                                label("BG-18"),
                                fields {
                                    text("BT-87", c.primaryAccountNumber)
                                    text("BT-88", c.holderName)
                                },
                            )
                        },
                        p.directDebit?.let { dd ->
                            section(
                                "BG-19",
                                label("BG-19"),
                                fields {
                                    text("BT-89", dd.mandateReference)
                                    text("BT-90", dd.creditorId)
                                    text("BT-91", dd.debitedAccountId)
                                },
                            )
                        },
                    ),
            )
        }

    private fun allowancesAndCharges(
        group: String,
        items: List<AllowanceCharge>,
        allowance: Boolean,
    ) = group(group, items.map { allowanceCharge(group, it, if (allowance) DOC_ALLOWANCE else DOC_CHARGE) })

    private fun totals() =
        with(invoice.totals) {
            section(
                "BG-22",
                label("BG-22"),
                fields {
                    money("BT-106", lineNetTotal)
                    money("BT-107", allowanceTotal)
                    money("BT-108", chargeTotal)
                    money("BT-109", totalWithoutVat)
                    money("BT-110", totalVat)
                    money("BT-111", totalVatInAccountingCurrency, invoice.vatAccountingCurrency)
                    money("BT-112", totalWithVat)
                    money("BT-113", paidAmount)
                    money("BT-114", roundingAmount)
                    money("BT-115", amountDue)
                },
            )
        }

    private fun vatBreakdown() =
        group(
            "BG-23",
            invoice.vatBreakdown.map { v ->
                section(
                    "BG-23",
                    label("BG-23"),
                    fields {
                        money("BT-116", v.taxableAmount)
                        money("BT-117", v.taxAmount)
                        code("BT-118", v.categoryCode, CodeLists.vatCategory)
                        percent("BT-119", v.rate)
                        text("BT-120", v.exemptionReason)
                        text("BT-121", v.exemptionReasonCode)
                    },
                )
            },
        )

    private fun supportingDocuments() =
        group(
            "BG-24",
            invoice.supportingDocuments.map { doc ->
                section(
                    "BG-24",
                    label("BG-24"),
                    fields {
                        text("BT-122", doc.reference)
                        text("BT-123", doc.description)
                        text("BT-124", doc.externalLocation)
                        doc.attachment?.let { a ->
                            val details = listOfNotNull(a.mimeCode, "${number(BigDecimal(a.size), 0)} Bytes").joinToString(", ")
                            text("BT-125", "${a.filename ?: word("Anhang", "Attachment")} ($details)")
                        }
                    },
                )
            },
        )

    private fun lines() = group("BG-25", invoice.lines.map { line(it, sub = false) })

    private fun thirdPartyPayments() =
        group(
            "BG-DEX-09",
            invoice.thirdPartyPayments.map { p ->
                section(
                    "BG-DEX-09",
                    label("BG-DEX-09"),
                    fields {
                        text("BT-DEX-001", p.type)
                        money("BT-DEX-002", p.amount)
                        text("BT-DEX-003", p.description)
                    },
                )
            },
        )

    private fun line(
        line: InvoiceLine,
        sub: Boolean,
    ): ViewSection? {
        val title = listOfNotNull(if (sub) word("Unterposition", "Sub-line") else label("BG-25"), line.id).joinToString(" ")
        return section(
            "BG-25",
            title,
            fields {
                text("BT-126", line.id)
                text("BT-153", line.item.name)
                quantity("BT-129", line.quantity)
                code("BT-130", line.unitCode, CodeLists.unit)
                money("BT-131", line.netAmount)
                text("BT-127", line.note)
                id("BT-128", line.objectIdentifier)
                text("BT-132", line.orderLineReference)
                text("BT-133", line.buyerAccountingReference)
            },
            listOfNotNull(
                period("BG-26", line.period, "BT-134", "BT-135"),
                with(line.price) {
                    section(
                        "BG-29",
                        label("BG-29"),
                        fields {
                            money("BT-146", netPrice)
                            money("BT-147", discount)
                            money("BT-148", grossPrice)
                            quantity("BT-149", baseQuantity)
                            code("BT-150", baseQuantityUnitCode, CodeLists.unit)
                        },
                    )
                },
                section(
                    "BG-30",
                    label("BG-30"),
                    fields {
                        code("BT-151", line.vatCategoryCode, CodeLists.vatCategory)
                        percent("BT-152", line.vatRate)
                    },
                ),
                with(line.item) {
                    section(
                        "BG-31",
                        label("BG-31"),
                        fields {
                            text("BT-154", description)
                            text("BT-155", sellerId)
                            text("BT-156", buyerId)
                            id("BT-157", standardId)
                            classifications.forEach { id("BT-158", it) }
                            text("BT-159", originCountry)
                        },
                    )
                },
                line.item.attributes
                    .takeIf { it.isNotEmpty() }
                    ?.let { attrs ->
                        section(
                            "BG-32",
                            label("BG-32"),
                            fields {
                                attrs.forEach { a ->
                                    text("BT-160", a.name)
                                    text("BT-161", a.value)
                                }
                            },
                        )
                    },
            ) +
                line.allowances.map { allowanceCharge("BG-27", it, LINE_ALLOWANCE) } +
                line.charges.map { allowanceCharge("BG-28", it, LINE_CHARGE) } +
                line.subLines.map { line(it, sub = true) },
        )
    }

    // --- shared parts ---------------------------------------------------------------

    /** BT IDs of an allowance/charge in its context: amount, base, percentage, VAT category, VAT rate, reason, reason code. */
    private class AcIds(
        val amount: String,
        val base: String,
        val percent: String,
        val category: String?,
        val rate: String?,
        val reason: String,
        val reasonCode: String,
    )

    private fun allowanceCharge(
        group: String,
        ac: AllowanceCharge,
        ids: AcIds,
    ) = section(
        group,
        label(group),
        fields {
            money(ids.amount, ac.amount)
            money(ids.base, ac.baseAmount)
            percent(ids.percent, ac.percentage)
            ids.category?.let { code(it, ac.vatCategoryCode, CodeLists.vatCategory) }
            ids.rate?.let { percent(it, ac.vatRate) }
            text(ids.reason, ac.reason)
            text(ids.reasonCode, ac.reasonCode)
        },
    )

    /** [ids]: line 1, line 2, line 3, city, post code, region, country. */
    private fun address(
        group: String,
        address: Address?,
        ids: List<String>,
    ) = address?.let { a ->
        section(
            group,
            label(group),
            fields {
                text(ids[0], a.line1)
                text(ids[1], a.line2)
                text(ids[2], a.line3)
                text(ids[4], a.postCode)
                text(ids[3], a.city)
                text(ids[5], a.subdivision)
                text(ids[6], a.countryCode)
            },
        )
    }

    private fun contact(
        group: String,
        contact: Contact?,
        ids: List<String>,
    ) = contact?.let { c ->
        section(
            group,
            label(group),
            fields {
                text(ids[0], c.point)
                text(ids[1], c.phone)
                text(ids[2], c.email)
            },
        )
    }

    private fun period(
        group: String,
        period: Period?,
        start: String,
        end: String,
    ) = period?.let { p ->
        section(
            group,
            label(group),
            fields {
                date(start, p.start)
                date(end, p.end)
            },
        )
    }

    private fun section(
        id: String,
        title: String,
        fields: List<ViewField>,
        sections: List<ViewSection?> = emptyList(),
    ): ViewSection? {
        val present = sections.filterNotNull()
        return if (fields.isEmpty() && present.isEmpty()) null else ViewSection(id, title, fields, present)
    }

    /** Several sections of the same group (e.g. all VAT rates) under one heading. */
    private fun group(
        id: String,
        items: List<ViewSection?>,
    ): ViewSection? {
        val present = items.filterNotNull()
        if (present.isEmpty()) return null
        return ViewSection(id, groupTitle(id), emptyList(), present)
    }

    private fun groupTitle(id: String): String =
        when (id) {
            "BG-1" -> word("Hinweise", "Notes")
            "BG-3" -> word("Vorausgegangene Rechnungen", "Preceding invoices")
            "BG-20" -> word("Nachlässe", "Allowances")
            "BG-21" -> word("Zuschläge", "Charges")
            "BG-23" -> word("Umsatzsteuer", "VAT")
            "BG-24" -> word("Rechnungsbegründende Unterlagen", "Supporting documents")
            "BG-25" -> word("Positionen", "Lines")
            "BG-DEX-09" -> word("Zahlungen an Dritte", "Third-party payments")
            else -> label(id)
        }

    private fun label(id: String): String = BtLabels.of(id, language) ?: id

    private inner class Fields {
        val list = mutableListOf<ViewField>()

        fun text(
            bt: String,
            value: String?,
        ) {
            val v = value?.trim()
            if (!v.isNullOrEmpty()) list += ViewField(bt, label(bt), v)
        }

        fun id(
            bt: String,
            value: Identifier?,
        ) {
            if (value == null || value.value.isBlank()) return
            val scheme = value.scheme?.takeIf { it.isNotBlank() }?.let { " (${word("Schema", "scheme")} $it)" } ?: ""
            text(bt, value.value.trim() + scheme)
        }

        fun date(
            bt: String,
            value: LocalDate?,
        ) = text(bt, value?.format(dates))

        fun code(
            bt: String,
            value: String?,
            list: Map<String, String>,
        ) = text(bt, value?.trim()?.takeIf { it.isNotEmpty() }?.let { CodeLists.describe(it, codes(list)) })

        fun money(
            bt: String,
            value: BigDecimal?,
            currencyCode: String? = currency,
        ) = text(bt, value?.let { listOfNotNull(number(it, 2), currencyCode).joinToString(" ") })

        fun percent(
            bt: String,
            value: BigDecimal?,
        ) = text(bt, value?.let { "${number(it, 0)} %" })

        fun quantity(
            bt: String,
            value: BigDecimal?,
        ) = text(bt, value?.let { number(it, 0) })
    }

    private fun fields(block: Fields.() -> Unit): List<ViewField> = Fields().apply(block).list

    /** Number with at least [minDecimals] decimals, keeping any further decimals written in the file. */
    private fun number(
        value: BigDecimal,
        minDecimals: Int,
    ): String {
        val format = DecimalFormat("#,##0", DecimalFormatSymbols(numberLocale))
        val scale = maxOf(minDecimals, value.stripTrailingZeros().scale().coerceAtLeast(0))
        format.minimumFractionDigits = scale
        format.maximumFractionDigits = scale
        return format.format(value)
    }

    private companion object {
        val DOC_ALLOWANCE = AcIds("BT-92", "BT-93", "BT-94", "BT-95", "BT-96", "BT-97", "BT-98")
        val DOC_CHARGE = AcIds("BT-99", "BT-100", "BT-101", "BT-102", "BT-103", "BT-104", "BT-105")
        val LINE_ALLOWANCE = AcIds("BT-136", "BT-137", "BT-138", null, null, "BT-139", "BT-140")
        val LINE_CHARGE = AcIds("BT-141", "BT-142", "BT-143", null, null, "BT-144", "BT-145")
    }
}
