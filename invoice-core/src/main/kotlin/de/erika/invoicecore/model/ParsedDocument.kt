package de.erika.invoicecore.model

/** Container and syntax as stored in `document.format` by the ERIKA app. */
public enum class InvoiceFormat {
    /** XRechnung in UBL syntax (BT-24 names the XRechnung CIUS). */
    XRECHNUNG_UBL,

    /** XRechnung in UN/CEFACT CII syntax. */
    XRECHNUNG_CII,

    /** Other EN 16931 UBL invoice, e.g. Peppol BIS Billing 3.0. */
    UBL,

    /** Other CII invoice, e.g. a bare ZUGFeRD/Factur-X XML. */
    CII,

    /** Hybrid PDF/A-3 with embedded CII XML (ZUGFeRD 2.x / Factur-X). */
    ZUGFERD,

    /** PDF without embedded invoice XML: "not an e-invoice (other invoice)" (IN-04). */
    PDF_ONLY,

    /** Readable file, but not a supported invoice format (e.g. ZUGFeRD 1.0, unknown XML). */
    UNSUPPORTED,
}

public enum class Syntax { UBL, CII }

/** Profile derived from the specification identifier (BT-24). */
public enum class Profile(
    /** False for profiles that don't count as e-invoices under § 14 UStG (requirements §5). */
    public val countsAsEInvoice: Boolean,
) {
    XRECHNUNG(true),
    EN16931(true),
    EXTENDED(true),
    BASIC(true),
    BASIC_WL(false),
    MINIMUM(false),
    UNKNOWN(false),
}

public data class DetectedFormat(
    val format: InvoiceFormat,
    val syntax: Syntax? = null,
    val profile: Profile? = null,
    /** BT-24 as written in the file. */
    val specificationIdentifier: String? = null,
    /** File name of the embedded XML in a hybrid PDF, e.g. `factur-x.xml`. */
    val embeddedFileName: String? = null,
    /** Why the file isn't a supported e-invoice, when [isEInvoice] is false. */
    val reason: NotEInvoiceReason? = null,
) {
    /** True if the file is a structured invoice that counts as an e-invoice. */
    val isEInvoice: Boolean
        get() = reason == null && profile?.countsAsEInvoice == true
}

public enum class NotEInvoiceReason {
    /** PDF without embedded invoice XML (IN-04). */
    PLAIN_PDF,

    /** ZUGFeRD/Factur-X MINIMUM or BASIC-WL: readable, but not a valid e-invoice. */
    INSUFFICIENT_PROFILE,

    /** ZUGFeRD 1.0, which ERIKA doesn't read. */
    ZUGFERD_1,

    /** XML that is neither a UBL nor a CII invoice. */
    UNKNOWN_XML,

    /** Specification identifier (BT-24) not recognised. */
    UNKNOWN_PROFILE,
}

/** Result of [de.erika.invoicecore.InvoiceParser.parse]. */
public data class ParsedDocument(
    val format: DetectedFormat,
    /** Null for PDF_ONLY and UNSUPPORTED. */
    val invoice: Invoice?,
    /** Non-fatal problems met while reading, e.g. an unparseable date. No invoice content. */
    val warnings: List<ParseWarning> = emptyList(),
)

public data class ParseWarning(
    val code: String,
    /** Business term concerned, if known. */
    val businessTerm: String?,
    val message: String,
)
