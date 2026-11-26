package de.erika.reportinvoicing

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Billing settings for reports and which invoice billed which report (MARA Stage 8, EX-04, M-AC-04). */
@Component
internal class ReportInvoicingAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "berichtsabrechnung.json"

    override fun collect(workspaceId: UUID): Any {
        val ws = "WHERE workspace_id = current_workspace_id()"
        return mapOf(
            "settings" to jdbc.jsonRows(json, "SELECT * FROM report_invoicing_settings $ws"),
            "customerSettings" to jdbc.jsonRows(json, "SELECT * FROM report_invoicing_customer $ws ORDER BY customer_id"),
            "reportInvoices" to jdbc.jsonRows(json, "SELECT * FROM report_invoice_link $ws ORDER BY created_at, report_id"),
        )
    }
}
