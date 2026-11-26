package de.erika.reportinvoicing

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

/** A customer's own settings; null fields use the firm's. */
data class CustomerBilling(
    val billingUnitMinutes: Int? = null,
    val travelMode: TravelMode? = null,
    val travelFlatAmount: BigDecimal? = null,
    val travelHourlyRate: BigDecimal? = null,
)

@Repository
internal class ReportInvoicingRepository(
    private val jdbc: JdbcClient,
) {
    fun settings(): BillingRules =
        jdbc
            .sql(
                "SELECT billing_unit_minutes, default_hourly_rate, travel_mode, travel_flat_amount, travel_hourly_rate " +
                    "FROM report_invoicing_settings WHERE workspace_id = current_workspace_id()",
            ).query { rs, _ ->
                BillingRules(
                    rs.getInt("billing_unit_minutes"),
                    rs.getBigDecimal("default_hourly_rate"),
                    TravelMode.valueOf(rs.getString("travel_mode")),
                    rs.getBigDecimal("travel_flat_amount"),
                    rs.getBigDecimal("travel_hourly_rate"),
                )
            }.optional()
            .orElse(DEFAULTS)

    fun saveSettings(
        workspaceId: UUID,
        rules: BillingRules,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO report_invoicing_settings
                    (workspace_id, billing_unit_minutes, default_hourly_rate, travel_mode, travel_flat_amount, travel_hourly_rate)
                VALUES (:ws, :unit, :rate, :mode, :flat, :travel)
                ON CONFLICT (workspace_id) DO UPDATE SET billing_unit_minutes = EXCLUDED.billing_unit_minutes,
                    default_hourly_rate = EXCLUDED.default_hourly_rate, travel_mode = EXCLUDED.travel_mode,
                    travel_flat_amount = EXCLUDED.travel_flat_amount, travel_hourly_rate = EXCLUDED.travel_hourly_rate, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("unit", rules.billingUnitMinutes)
            .param("rate", rules.defaultHourlyRate)
            .param("mode", rules.travelMode.name)
            .param("flat", rules.travelFlatAmount)
            .param("travel", rules.travelHourlyRate)
            .update()
    }

    fun customer(customerId: UUID): CustomerBilling =
        jdbc
            .sql(
                "SELECT billing_unit_minutes, travel_mode, travel_flat_amount, travel_hourly_rate FROM report_invoicing_customer " +
                    "WHERE customer_id = :id AND workspace_id = current_workspace_id()",
            ).param("id", customerId)
            .query { rs, _ -> rs.toCustomer() }
            .optional()
            .orElse(CustomerBilling())

    fun saveCustomer(
        workspaceId: UUID,
        customerId: UUID,
        c: CustomerBilling,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO report_invoicing_customer
                    (workspace_id, customer_id, billing_unit_minutes, travel_mode, travel_flat_amount, travel_hourly_rate)
                VALUES (:ws, :customer, :unit, :mode, :flat, :travel)
                ON CONFLICT (workspace_id, customer_id) DO UPDATE SET billing_unit_minutes = EXCLUDED.billing_unit_minutes,
                    travel_mode = EXCLUDED.travel_mode, travel_flat_amount = EXCLUDED.travel_flat_amount,
                    travel_hourly_rate = EXCLUDED.travel_hourly_rate, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("customer", customerId)
            .param("unit", c.billingUnitMinutes)
            .param("mode", c.travelMode?.name)
            .param("flat", c.travelFlatAmount)
            .param("travel", c.travelHourlyRate)
            .update()
    }

    /** Replaces stale links of these reports (their earlier draft was deleted) and records the new invoice. */
    fun link(
        workspaceId: UUID,
        invoiceId: UUID,
        reports: Map<UUID, UUID>,
        actorId: UUID,
    ) {
        jdbc
            .sql("DELETE FROM report_invoice_link WHERE report_id IN (:ids) AND workspace_id = current_workspace_id()")
            .param("ids", reports.keys)
            .update()
        reports.forEach { (report, workOrder) ->
            jdbc
                .sql(
                    "INSERT INTO report_invoice_link (workspace_id, report_id, work_order_id, invoice_id, created_by) " +
                        "VALUES (:ws, :report, :order, :invoice, :actor)",
                ).param("ws", workspaceId)
                .param("report", report)
                .param("order", workOrder)
                .param("invoice", invoiceId)
                .param("actor", actorId)
                .update()
        }
    }

    fun workOrdersOf(invoiceId: UUID): List<UUID> =
        jdbc
            .sql("SELECT DISTINCT work_order_id FROM report_invoice_link WHERE invoice_id = :id AND workspace_id = current_workspace_id()")
            .param("id", invoiceId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    private fun ResultSet.toCustomer() =
        CustomerBilling(
            getObject("billing_unit_minutes") as Int?,
            getString("travel_mode")?.let(TravelMode::valueOf),
            getBigDecimal("travel_flat_amount"),
            getBigDecimal("travel_hourly_rate"),
        )

    companion object {
        val DEFAULTS = BillingRules(15, null, TravelMode.TIME, null, null)
    }
}
