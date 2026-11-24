package de.erika.creator

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Invoice settings, customers, articles, drafts, issued invoices, deliveries and recurring invoices (EX-04, NF-09). */
@Component
internal class CreatorAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "rechnungserstellung.json"

    override fun collect(workspaceId: UUID): Any {
        val ws = "WHERE workspace_id = current_workspace_id()"
        return mapOf(
            "settings" to jdbc.jsonRows(json, "SELECT * FROM invoice_settings $ws"),
            "customers" to jdbc.jsonRows(json, "SELECT * FROM party $ws ORDER BY created_at, id"),
            "articles" to jdbc.jsonRows(json, "SELECT * FROM article $ws ORDER BY created_at, id"),
            "numberSequences" to jdbc.jsonRows(json, "SELECT sequence_key, next_value FROM number_sequence $ws ORDER BY sequence_key"),
            "drafts" to jdbc.jsonRows(json, "SELECT * FROM invoice_draft $ws ORDER BY created_at, id"),
            "invoices" to jdbc.jsonRows(json, "SELECT * FROM invoice $ws ORDER BY issued_at, id"),
            "deliveries" to jdbc.jsonRows(json, "SELECT * FROM invoice_delivery $ws ORDER BY created_at, id"),
            "recurringInvoices" to jdbc.jsonRows(json, "SELECT * FROM recurring_invoice $ws ORDER BY created_at, id"),
        )
    }
}
