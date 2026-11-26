package de.erika.reportinvoicing.web

import de.erika.contract.api.ReportInvoicingApi
import de.erika.contract.model.BillableReport
import de.erika.contract.model.BillableReportList
import de.erika.contract.model.CustomerBillingSettings
import de.erika.contract.model.ReportInvoiceCreated
import de.erika.contract.model.ReportInvoiceLine
import de.erika.contract.model.ReportInvoicePreview
import de.erika.contract.model.ReportInvoiceRequest
import de.erika.contract.model.ReportInvoicingSettings
import de.erika.contract.model.ReportSelection
import de.erika.contract.model.ReportState
import de.erika.contract.model.VatRate
import de.erika.creator.LineVatRate
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.Idempotency
import de.erika.reportinvoicing.BillingRules
import de.erika.reportinvoicing.CustomerBilling
import de.erika.reportinvoicing.InvoiceLineDraft
import de.erika.reportinvoicing.LineKind
import de.erika.reportinvoicing.ReportInvoicing
import de.erika.reportinvoicing.TravelMode
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import de.erika.contract.model.TravelMode as ApiTravelMode

/**
 * Invoicing from reports (api/openapi.yaml, tag `report-invoicing`, MARA Stage 8). Reading for
 * every web role; the preview and the invoice for OWNER and MEMBER; billing settings for owners.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ReportInvoicingController(
    private val service: ReportInvoicing,
    private val idempotency: Idempotency,
) : ReportInvoicingApi {
    override fun getReportInvoicingSettings(): ResponseEntity<ReportInvoicingSettings> = ResponseEntity.ok(service.settings().toApi())

    @PreAuthorize("hasRole('OWNER')")
    override fun updateReportInvoicingSettings(reportInvoicingSettings: ReportInvoicingSettings): ResponseEntity<ReportInvoicingSettings> {
        val p = ErikaPrincipal.current()
        val s = reportInvoicingSettings
        val rules =
            BillingRules(
                billingUnitMinutes = s.billingUnitMinutes.value,
                defaultHourlyRate = s.defaultHourlyRate?.let(::BigDecimal),
                travelMode = TravelMode.valueOf(s.travelMode.value),
                travelFlatAmount = s.travelFlatAmount?.let(::BigDecimal),
                travelHourlyRate = s.travelHourlyRate?.let(::BigDecimal),
            )
        return ResponseEntity.ok(service.saveSettings(p.workspaceId, p.userId, rules).toApi())
    }

    override fun getCustomerBillingSettings(customerId: UUID): ResponseEntity<CustomerBillingSettings> =
        ResponseEntity.ok(service.customerSettings(customerId).toApi())

    @PreAuthorize("hasRole('OWNER')")
    override fun updateCustomerBillingSettings(
        customerId: UUID,
        customerBillingSettings: CustomerBillingSettings,
    ): ResponseEntity<CustomerBillingSettings> {
        val p = ErikaPrincipal.current()
        val s = customerBillingSettings
        val model =
            CustomerBilling(
                billingUnitMinutes = s.billingUnitMinutes?.value,
                travelMode = s.travelMode?.let { TravelMode.valueOf(it.value) },
                travelFlatAmount = s.travelFlatAmount?.let(::BigDecimal),
                travelHourlyRate = s.travelHourlyRate?.let(::BigDecimal),
            )
        return ResponseEntity.ok(service.saveCustomerSettings(p.workspaceId, p.userId, customerId, model).toApi())
    }

    override fun listBillableReports(customerId: UUID?): ResponseEntity<BillableReportList> =
        ResponseEntity.ok(
            BillableReportList(
                service.billable(customerId).map {
                    BillableReport(
                        reportId = it.report.reportId,
                        workOrderId = it.report.workOrderId,
                        workOrderTitle = it.report.workOrderTitle,
                        customerId = it.report.customerId,
                        customerName = it.customerName,
                        version = it.report.version,
                        state = ReportState.forValue(it.report.state),
                        signedAt = it.report.signedAt,
                    )
                },
            ),
        )

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun previewReportInvoice(reportSelection: ReportSelection): ResponseEntity<ReportInvoicePreview> {
        val preview = service.preview(reportSelection.reportIds)
        return ResponseEntity.ok(
            ReportInvoicePreview(
                customerId = preview.customerId,
                customerName = preview.customerName,
                privateCustomer = preview.privateCustomer,
                lines = preview.lines.map { it.toApi() },
                totalNet = preview.totalNet.toPlainString(),
            ),
        )
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createReportInvoice(
        idempotencyKey: String,
        reportInvoiceRequest: ReportInvoiceRequest,
    ): ResponseEntity<ReportInvoiceCreated> {
        val p = ErikaPrincipal.current()
        val r = reportInvoiceRequest
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createReportInvoice") {
                service.create(
                    p.workspaceId,
                    p.userId,
                    r.reportIds,
                    r.lines.map { it.toModel() },
                    r.propertyRelated ?: false,
                    r.format?.let { OutputFormat.valueOf(it.value) },
                )
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportInvoiceCreated(result.resourceId))
    }

    private fun BillingRules.toApi() =
        ReportInvoicingSettings(
            billingUnitMinutes = ReportInvoicingSettings.BillingUnitMinutes.forValue(billingUnitMinutes),
            travelMode = ApiTravelMode.forValue(travelMode.name),
            defaultHourlyRate = defaultHourlyRate?.plain(),
            travelFlatAmount = travelFlatAmount?.plain(),
            travelHourlyRate = travelHourlyRate?.plain(),
        )

    private fun CustomerBilling.toApi() =
        CustomerBillingSettings(
            billingUnitMinutes = billingUnitMinutes?.let { CustomerBillingSettings.BillingUnitMinutes.forValue(it) },
            travelMode = travelMode?.let { ApiTravelMode.forValue(it.name) },
            travelFlatAmount = travelFlatAmount?.plain(),
            travelHourlyRate = travelHourlyRate?.plain(),
        )

    private fun InvoiceLineDraft.toApi() =
        ReportInvoiceLine(
            name = name,
            quantity = quantity.plain(),
            unitCode = unitCode,
            // The contract needs a price: a missing one is sent as 0 and marked.
            netPrice = (netPrice ?: BigDecimal.ZERO).plain(),
            vatRate = VatRate.forValue(vatRate.name),
            laborCost = laborCost,
            reportId = reportId,
            kind = kind?.let { ReportInvoiceLine.Kind.forValue(it.name) },
            articleId = articleId,
            articleNumber = articleNumber,
            description = description,
            net = net.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            priceMissing = netPrice == null,
        )

    private fun ReportInvoiceLine.toModel(): InvoiceLineDraft {
        val q = BigDecimal(quantity)
        val price = BigDecimal(netPrice)
        return InvoiceLineDraft(
            reportId = reportId,
            kind = kind?.takeIf { it != ReportInvoiceLine.Kind.OTHER }?.let { LineKind.valueOf(it.value) },
            name = name,
            description = description,
            quantity = q,
            unitCode = unitCode,
            netPrice = price,
            vatRate = LineVatRate.valueOf(vatRate.value),
            laborCost = laborCost,
            net = q.multiply(price).setScale(2, RoundingMode.HALF_UP),
            articleId = articleId,
            articleNumber = articleNumber,
        )
    }

    private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()
}
