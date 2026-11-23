package de.erika.invoicecore.write

import de.erika.invoicecore.model.Invoice

/** The e-invoice formats ERIKA creates (ERIKA CR-02). */
public enum class OutputFormat(
    /** BT-24 written into the file. */
    public val specificationIdentifier: String,
    public val mediaType: String,
    public val fileExtension: String,
) {
    /** XRechnung 3.0 in UN/CEFACT CII syntax. */
    XRECHNUNG_CII(InvoiceWriter.XRECHNUNG_3_0, "application/xml", "xml"),

    /** XRechnung 3.0 in UBL 2.1 syntax (Invoice, or CreditNote for type code 381). */
    XRECHNUNG_UBL(InvoiceWriter.XRECHNUNG_3_0, "application/xml", "xml"),

    /** ZUGFeRD 2.x / Factur-X profile EN 16931: PDF/A-3b with embedded CII XML. */
    ZUGFERD_EN16931(InvoiceWriter.EN16931, "application/pdf", "pdf"),
}

/**
 * Writes an invoice in the neutral model as an e-invoice file (CR-02):
 * XRechnung CII, XRechnung UBL or ZUGFeRD EN 16931.
 *
 * The caller provides the content (number, parties, lines, VAT categories, payment); the
 * writer sets the specification identifier (BT-24) and business process (BT-23) for the
 * format and computes the amounts with [InvoiceCalculator]. It does not validate: run the
 * file through the official validation (KoSIT, veraPDF) before issuing it.
 *
 * Deterministic: the same invoice gives the same bytes. Thread-safe.
 *
 * Business terms written: BT-1 … BT-3, BT-5, BT-9 … BT-14, BT-19, BT-20, BG-1, BT-23, BT-24,
 * BG-3, BG-4 (BT-27 … BT-34, BG-5, BG-6), BG-7 (BT-44 … BT-49, BG-8, BG-9), BG-13 (BT-70 …
 * BT-72, BG-14, BG-15), BG-16 (BT-81 … BT-86, BT-89 … BT-91), BG-20, BG-21, BG-22, BG-23 and
 * BG-25 with BT-126 … BT-131, BG-26 … BG-29 (net price, base quantity), BG-30, BT-153 … BT-156.
 */
public object InvoiceWriter {
    /** BT-24 of XRechnung 3.0 (KoSIT). */
    public const val XRECHNUNG_3_0: String = "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0"

    /** BT-24 of the EN 16931 core, used by ZUGFeRD 2.x / Factur-X profile EN 16931. */
    public const val EN16931: String = "urn:cen.eu:en16931:2017"

    /** BT-23 (Peppol billing process), required by XRechnung 3.0 (PEPPOL-EN16931-R001). */
    public const val BILLING_PROCESS: String = "urn:fdc:peppol.eu:2017:poacc:billing:01.0"

    /** The invoice as it will be written: identifiers for [format] set and amounts computed. */
    public fun prepare(
        invoice: Invoice,
        format: OutputFormat,
    ): Invoice =
        InvoiceCalculator.complete(
            invoice.copy(
                specificationIdentifier = format.specificationIdentifier,
                businessProcessType = invoice.businessProcessType ?: BILLING_PROCESS,
            ),
        )

    public fun write(
        invoice: Invoice,
        format: OutputFormat,
    ): ByteArray {
        val prepared = prepare(invoice, format)
        return when (format) {
            OutputFormat.XRECHNUNG_CII -> CiiWriter.write(prepared)
            OutputFormat.XRECHNUNG_UBL -> UblWriter.write(prepared)
            OutputFormat.ZUGFERD_EN16931 -> ZugferdPdf.write(prepared, CiiWriter.write(prepared))
        }
    }

    /** A file name for the written invoice, e.g. `RE-2026-0001.xml`. Only safe characters. */
    public fun fileName(
        invoice: Invoice,
        format: OutputFormat,
    ): String {
        val base = (invoice.number ?: "rechnung").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "$base.${format.fileExtension}"
    }
}
