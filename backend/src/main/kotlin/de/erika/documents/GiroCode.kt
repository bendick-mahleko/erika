package de.erika.documents

import java.math.BigDecimal
import java.math.RoundingMode

/** Bank transfer details of a received invoice (AR-06). */
data class Transfer(
    val name: String,
    val iban: String,
    val bic: String?,
    val amount: BigDecimal?,
    val currency: String,
    val reference: String?,
) {
    /** The EPC QR code payload ("GiroCode", EPC069-12 version 002). */
    val epcPayload: String get() = GiroCode.payload(this)
}

/**
 * EPC069-12 "Quick Response Code: guidelines to enable data capture for the initiation of a SEPA
 * credit transfer", version 002: fields separated by LF, UTF-8, at most 331 bytes. Banking apps
 * read it and fill in the transfer (AR-06).
 */
object GiroCode {
    private const val MAX_NAME = 70
    private const val MAX_REFERENCE = 140
    private val MAX_AMOUNT = BigDecimal("999999999.99")

    /** Transfer details if the invoice can be paid by SEPA credit transfer in EUR, else null. */
    fun transferOf(
        payeeName: String?,
        sellerName: String?,
        iban: String?,
        bic: String?,
        amountDue: BigDecimal?,
        currency: String?,
        remittance: String?,
        invoiceNumber: String?,
    ): Transfer? {
        val cleanIban = iban?.replace(" ", "")?.uppercase()?.takeIf { IBAN.matches(it) } ?: return null
        if (currency != null && currency != "EUR") return null
        val name = (payeeName ?: sellerName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val amount = amountDue?.takeIf { it.signum() > 0 && it <= MAX_AMOUNT }?.setScale(2, RoundingMode.HALF_UP)
        return Transfer(
            name = name.take(MAX_NAME),
            iban = cleanIban,
            bic = bic?.replace(" ", "")?.uppercase()?.takeIf { BIC.matches(it) },
            amount = amount,
            currency = "EUR",
            reference = (remittance ?: invoiceNumber)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REFERENCE),
        )
    }

    fun payload(t: Transfer): String =
        listOf(
            "BCD",
            "002",
            "1",
            "SCT",
            t.bic.orEmpty(),
            clean(t.name),
            t.iban,
            t.amount?.let { "EUR" + it.toPlainString() }.orEmpty(),
            "",
            "",
            t.reference?.let(::clean).orEmpty(),
        ).joinToString("\n").trimEnd('\n')

    /** No line breaks inside a field. */
    private fun clean(s: String) = s.replace('\r', ' ').replace('\n', ' ').trim()

    private val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
    private val BIC = Regex("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$")
}
