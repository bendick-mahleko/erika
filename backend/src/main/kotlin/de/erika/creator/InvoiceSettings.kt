package de.erika.creator

import de.erika.identity.WorkspaceProfile
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.audit.AuditableEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Seller data, numbering and defaults of a workspace (`invoice_settings`); defaults until first saved. */
data class InvoiceSettings(
    val contactName: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val legalRegistration: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val accountHolder: String? = null,
    val kleinunternehmer: Boolean = false,
    val numberPattern: String = "RE-{YYYY}-{NNNN}",
    val creditNotePattern: String = "GS-{YYYY}-{NNNN}",
    val defaultFormat: OutputFormat = OutputFormat.ZUGFERD_EN16931,
    val paymentTermsDays: Int = 14,
    val version: Int = 0,
    val updatedAt: OffsetDateTime? = null,
) {
    fun patternFor(kind: InvoiceKind): NumberPattern =
        checkNotNull(NumberPattern.parse(if (kind.usesCreditNotePattern) creditNotePattern else numberPattern)) {
            "Stored number pattern is invalid"
        }

    /**
     * Seller data every invoice needs (§ 14 Abs. 4 UStG, EN 16931, Software Design §5), as
     * problems with `settings.`/`seller.` paths. XRechnung needs the contact as well (BR-DE-2,
     * BR-DE-5 … 7) and an electronic address (PEPPOL-EN16931-R020).
     */
    fun problems(
        seller: WorkspaceProfile,
        xrechnung: Boolean,
    ): List<Problem> =
        buildList {
            if (seller.street.isNullOrBlank()) add(Problem("seller.street", "SETTINGS_INCOMPLETE"))
            if (seller.postalCode.isNullOrBlank()) add(Problem("seller.postalCode", "SETTINGS_INCOMPLETE"))
            if (seller.city.isNullOrBlank()) add(Problem("seller.city", "SETTINGS_INCOMPLETE"))
            if (seller.vatId.isNullOrBlank() && seller.taxNumber.isNullOrBlank()) add(Problem("seller.taxId", "SETTINGS_INCOMPLETE"))
            if (iban.isNullOrBlank()) add(Problem("settings.iban", "SETTINGS_INCOMPLETE"))
            if (xrechnung) {
                if (contactName.isNullOrBlank()) add(Problem("settings.contactName", "REQUIRED_FOR_XRECHNUNG"))
                if (contactPhone.isNullOrBlank()) add(Problem("settings.contactPhone", "REQUIRED_FOR_XRECHNUNG"))
                if (contactEmail.isNullOrBlank()) add(Problem("settings.contactEmail", "REQUIRED_FOR_XRECHNUNG"))
            }
        }
}

data class InvoiceSettingsChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val fields: List<String>,
) : AuditableEvent {
    override val action = "creator.settings.changed"
    override val details get() = mapOf("fields" to fields.sorted().joinToString(","))
}

@Repository
internal class InvoiceSettingsRepository(
    private val jdbc: JdbcClient,
) {
    fun find(workspaceId: UUID): InvoiceSettings =
        jdbc
            .sql("SELECT * FROM invoice_settings WHERE workspace_id = :ws")
            .param("ws", workspaceId)
            .query(::map)
            .optional()
            .orElse(InvoiceSettings())

    /** Saves if [expectedVersion] is current (0 for a workspace that has never saved). Returns the new row, or null. */
    fun save(
        workspaceId: UUID,
        expectedVersion: Int,
        s: InvoiceSettings,
    ): InvoiceSettings? {
        jdbc
            .sql("INSERT INTO invoice_settings (workspace_id) VALUES (:ws) ON CONFLICT DO NOTHING")
            .param("ws", workspaceId)
            .update()
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE invoice_settings
                       SET contact_name = :contactName, contact_phone = :contactPhone, contact_email = :contactEmail,
                           legal_registration = :legal, iban = :iban, bic = :bic, account_holder = :holder,
                           kleinunternehmer = :klein, number_pattern = :pattern, credit_note_pattern = :creditPattern,
                           default_format = :format, payment_terms_days = :days, version = version + 1, updated_at = now()
                     WHERE workspace_id = :ws AND version = :expected
                    """.trimIndent(),
                ).param("ws", workspaceId)
                .param("contactName", s.contactName)
                .param("contactPhone", s.contactPhone)
                .param("contactEmail", s.contactEmail)
                .param("legal", s.legalRegistration)
                .param("iban", s.iban)
                .param("bic", s.bic)
                .param("holder", s.accountHolder)
                .param("klein", s.kleinunternehmer)
                .param("pattern", s.numberPattern)
                .param("creditPattern", s.creditNotePattern)
                .param("format", s.defaultFormat.name)
                .param("days", s.paymentTermsDays)
                .param("expected", expectedVersion)
                .update()
        return if (updated == 1) find(workspaceId) else null
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = InvoiceSettings(
        contactName = rs.getString("contact_name"),
        contactPhone = rs.getString("contact_phone"),
        contactEmail = rs.getString("contact_email"),
        legalRegistration = rs.getString("legal_registration"),
        iban = rs.getString("iban"),
        bic = rs.getString("bic"),
        accountHolder = rs.getString("account_holder"),
        kleinunternehmer = rs.getBoolean("kleinunternehmer"),
        numberPattern = rs.getString("number_pattern"),
        creditNotePattern = rs.getString("credit_note_pattern"),
        defaultFormat = OutputFormat.valueOf(rs.getString("default_format")),
        paymentTermsDays = rs.getInt("payment_terms_days"),
        version = rs.getInt("version"),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
    )
}

/**
 * Invoice numbering (CR-03, BR-04). [next] locks the workspace's counter for the sequence key
 * with `SELECT … FOR UPDATE` until the issuing transaction ends: parallel issues wait, and a
 * rolled-back issue gives its number back, so numbers are unique and without gaps.
 */
@Repository
internal class NumberSequences(
    private val jdbc: JdbcClient,
) {
    fun next(
        workspaceId: UUID,
        pattern: NumberPattern,
        date: LocalDate,
        taken: (String) -> Boolean,
    ): String {
        val key = pattern.sequenceKey(date)
        jdbc
            .sql(
                "INSERT INTO number_sequence (workspace_id, sequence_key, next_value) VALUES (:ws, :key, 1) ON CONFLICT DO NOTHING",
            ).param("ws", workspaceId)
            .param("key", key)
            .update()
        var value =
            jdbc
                .sql("SELECT next_value FROM number_sequence WHERE workspace_id = :ws AND sequence_key = :key FOR UPDATE")
                .param("ws", workspaceId)
                .param("key", key)
                .query(Long::class.java)
                .single()
        // A number can already exist only after the pattern was changed back; skip it rather than fail.
        while (taken(pattern.format(date, value))) value++
        jdbc
            .sql("UPDATE number_sequence SET next_value = :next WHERE workspace_id = :ws AND sequence_key = :key")
            .param("next", value + 1)
            .param("ws", workspaceId)
            .param("key", key)
            .update()
        return pattern.format(date, value)
    }

    /** The number [next] would give now, without reserving it (preview in the settings). */
    fun peek(
        workspaceId: UUID,
        pattern: NumberPattern,
        date: LocalDate,
    ): String {
        val value =
            jdbc
                .sql("SELECT next_value FROM number_sequence WHERE workspace_id = :ws AND sequence_key = :key")
                .param("ws", workspaceId)
                .param("key", pattern.sequenceKey(date))
                .query(Long::class.java)
                .optional()
                .orElse(1L)
        return pattern.format(date, value)
    }
}
