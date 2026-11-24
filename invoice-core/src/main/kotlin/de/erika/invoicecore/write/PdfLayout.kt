package de.erika.invoicecore.write

/**
 * Layout options for the readable PDF of a ZUGFeRD invoice (CR-08). They change only the
 * picture, never the invoice data or the embedded XML; XRechnung (pure XML) ignores them.
 *
 * @property logo a PNG or JPEG, drawn top right on the first page (at most 160 × 44 pt, aspect
 *   ratio kept). At most [MAX_LOGO_BYTES].
 * @property accentColor `#RRGGBB` for the invoice title and the table header rule.
 */
public class PdfLayout(
    public val logo: ByteArray? = null,
    public val accentColor: String? = null,
) {
    init {
        logo?.let {
            require(it.size <= MAX_LOGO_BYTES) { "The logo must be at most ${MAX_LOGO_BYTES / 1024} KB" }
            require(imageType(it) != null) { "The logo must be a PNG or JPEG image" }
        }
        accentColor?.let { require(COLOR.matches(it)) { "The accent colour must look like #1A2B3C" } }
    }

    /** The accent colour as RGB fractions, or null. */
    internal val accentRgb: FloatArray?
        get() =
            accentColor?.let { c ->
                floatArrayOf(
                    c.substring(1, 3).toInt(16) / 255f,
                    c.substring(3, 5).toInt(16) / 255f,
                    c.substring(5, 7).toInt(16) / 255f,
                )
            }

    public companion object {
        public val DEFAULT: PdfLayout = PdfLayout()
        public const val MAX_LOGO_BYTES: Int = 512 * 1024
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        /** `image/png` or `image/jpeg` by magic bytes, else null. */
        public fun imageType(bytes: ByteArray): String? =
            when {
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() &&
                    bytes[3] == 'G'.code.toByte() -> "image/png"

                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"

                else -> null
            }
    }
}
