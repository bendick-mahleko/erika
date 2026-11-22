package de.erika.invoicecore

/** Why a file couldn't be read at all. */
public enum class ParseErrorCode {
    /** Larger than [InvoiceParser.MAX_FILE_BYTES]. */
    TOO_LARGE,

    /** Not well-formed XML. */
    MALFORMED_XML,

    /** DTD/entity declarations (XXE, billion laughs) are rejected. */
    FORBIDDEN_XML_CONSTRUCT,

    /** More than [InvoiceParser.MAX_LINES] invoice lines. */
    TOO_MANY_LINES,

    /** Damaged PDF, or one that can't be opened without a password. */
    MALFORMED_PDF,

    /** Neither XML nor PDF. */
    UNKNOWN_FILE_TYPE,
}

/**
 * The file can't be read. The message is safe to log: it never contains
 * invoice content.
 */
public class InvoiceParseException(
    public val code: ParseErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
