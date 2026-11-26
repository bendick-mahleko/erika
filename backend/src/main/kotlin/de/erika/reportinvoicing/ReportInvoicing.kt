package de.erika.reportinvoicing

import de.erika.creator.ArticleDirectory
import de.erika.creator.CustomerDirectory
import de.erika.creator.CustomerType
import de.erika.creator.InvoiceIssued
import de.erika.creator.InvoiceKind
import de.erika.creator.LineVatRate
import de.erika.creator.SourceInvoiceAttachment
import de.erika.creator.SourceInvoiceLine
import de.erika.creator.SourceInvoiceRequest
import de.erika.creator.SourceInvoices
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.audit.AuditableEvent
import de.erika.platform.web.ErikaException
import de.erika.reports.ReportBilling
import de.erika.reports.ReportDirectory
import de.erika.workorders.WorkOrderDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A signed report that isn't on an invoice yet. */
data class Billable(
    val report: ReportBilling,
    val customerName: String,
)

/** A proposed or edited invoice line. */
data class InvoiceLineDraft(
    val reportId: UUID?,
    val kind: LineKind?,
    val name: String,
    val description: String?,
    val quantity: BigDecimal,
    val unitCode: String,
    val netPrice: BigDecimal?,
    val vatRate: LineVatRate,
    val laborCost: Boolean,
    val net: BigDecimal,
    val articleId: UUID? = null,
    val articleNumber: String? = null,
)

data class Preview(
    val customerId: UUID,
    val customerName: String,
    val privateCustomer: Boolean,
    val lines: List<InvoiceLineDraft>,
    val totalNet: BigDecimal,
)

/** Billing settings changed (firm or one customer). */
data class ReportInvoicingSettingsChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val customerId: UUID?,
) : AuditableEvent {
    override val action = if (customerId == null) "reportinvoicing.settings.changed" else "reportinvoicing.customer.changed"
    override val details get() = customerId?.let { mapOf("customerId" to it.toString()) } ?: emptyMap()
}

/** An invoice was requested for signed reports. */
data class ReportInvoiceRequested(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val reportIds: List<UUID>,
) : AuditableEvent {
    override val action = "reportinvoicing.invoice.requested"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "reports" to reportIds.size.toString())
}

/**
 * Invoicing from reports (MARA Stage 8, M-IV-01 to M-IV-04): proposes lines from signed reports
 * with [ReportLineRules] and hands the invoice to `creator.SourceInvoices`, which issues it with
 * `InvoiceIssuer` in the worker. The signed report PDFs are embedded (BG-24). Once issued, the
 * work orders become INVOICED.
 */
@Service
class ReportInvoicing internal constructor(
    private val repo: ReportInvoicingRepository,
    private val reports: ReportDirectory,
    private val workOrders: WorkOrderDirectory,
    private val customers: CustomerDirectory,
    private val articles: ArticleDirectory,
    private val invoices: SourceInvoices,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun settings(): BillingRules = repo.settings()

    @Transactional(propagation = Propagation.MANDATORY)
    fun saveSettings(
        workspaceId: UUID,
        actorId: UUID,
        rules: BillingRules,
    ): BillingRules {
        repo.saveSettings(workspaceId, rules)
        events.publishEvent(ReportInvoicingSettingsChanged(workspaceId, actorId, null))
        return repo.settings()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun customerSettings(customerId: UUID): CustomerBilling {
        customers.find(customerId) ?: throw notFound()
        return repo.customer(customerId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun saveCustomerSettings(
        workspaceId: UUID,
        actorId: UUID,
        customerId: UUID,
        settings: CustomerBilling,
    ): CustomerBilling {
        customers.find(customerId) ?: throw notFound()
        repo.saveCustomer(workspaceId, customerId, settings)
        events.publishEvent(ReportInvoicingSettingsChanged(workspaceId, actorId, customerId))
        return repo.customer(customerId)
    }

    /** Signed reports (newest version of their work order) without an invoice, newest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun billable(customerId: UUID?): List<Billable> {
        val candidates = reports.lockedLatest()
        val invoiced = invoices.invoiceOf(SOURCE, candidates).keys
        val data =
            reports.billingData(candidates.filter { it !in invoiced }).values.filter {
                customerId == null ||
                    it.customerId == customerId
            }
        val names = customers.findAll(data.map { it.customerId }.distinct())
        return data
            .sortedByDescending { it.signedAt }
            .map { Billable(it, names[it.customerId]?.name ?: "") }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun preview(reportIds: List<UUID>): Preview {
        val selected = selected(reportIds)
        val customer = customers.find(selected.first().customerId) ?: throw notFound()
        val firm = repo.settings()
        val own = repo.customer(customer.id)
        val rules =
            firm.copy(
                billingUnitMinutes = own.billingUnitMinutes ?: firm.billingUnitMinutes,
                travelMode = own.travelMode ?: firm.travelMode,
                travelFlatAmount = own.travelFlatAmount ?: firm.travelFlatAmount,
                travelHourlyRate = own.travelHourlyRate ?: firm.travelHourlyRate,
            )
        val rates = workOrders.hourlyRates(selected.flatMap { r -> r.times.map { it.technicianId } }.toSet())
        val prices = articles.prices(selected.flatMap { r -> r.materials.mapNotNull { it.articleId } }.toSet())
        val byId = selected.associateBy { it.reportId }
        val proposed =
            ReportLineRules.lines(
                rules,
                selected.map { r ->
                    BillableReport(
                        r.reportId,
                        r.times.map {
                            val rate = rates[it.technicianId]
                            BillableTime(it.kind, it.startedAt, it.endedAt, rate?.hourlyRate, rate?.levelName)
                        },
                        r.materials.map { m ->
                            val article = m.articleId?.let(prices::get)
                            BillableMaterial(
                                description = article?.name ?: m.description,
                                quantity = m.quantity,
                                unitCode = m.unitCode,
                                netPrice = article?.netPrice,
                                articleId = article?.id,
                                articleNumber = article?.articleNumber,
                                vatRate = (article?.vatRate ?: LineVatRate.STANDARD_19).name,
                            )
                        },
                    )
                },
            )
        val lines =
            proposed.map { p ->
                val report = byId.getValue(p.report as UUID)
                val name =
                    when (p.kind) {
                        LineKind.WORK -> listOfNotNull("Arbeitszeit", p.technicians.singleOrNull()).joinToString(" ")
                        LineKind.TRAVEL -> if (p.minutes == null) "Anfahrtspauschale" else "Anfahrt"
                        LineKind.MATERIAL -> p.material!!.description
                    }
                InvoiceLineDraft(
                    reportId = report.reportId,
                    kind = p.kind,
                    name = name.take(200),
                    description = if (p.kind == LineKind.MATERIAL) null else describe(report),
                    quantity = p.quantity,
                    unitCode = p.unitCode,
                    netPrice = p.netPrice,
                    vatRate = p.material?.let { LineVatRate.valueOf(it.vatRate) } ?: LineVatRate.STANDARD_19,
                    laborCost = p.laborCost,
                    net = p.net,
                    articleId = p.material?.articleId,
                    articleNumber = p.material?.articleNumber,
                )
            }
        return Preview(customer.id, customer.name, customer.type == CustomerType.PRIVATE, lines, ReportLineRules.total(proposed))
    }

    /**
     * Creates the invoice for [reportIds] with the office's [lines] and requests issuing. 409
     * `REPORT_NOT_BILLABLE`, `ALREADY_INVOICED` or `REPORT_PDF_PENDING` (the worker hasn't
     * archived the signed PDF yet).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun create(
        workspaceId: UUID,
        actorId: UUID,
        reportIds: List<UUID>,
        lines: List<InvoiceLineDraft>,
        propertyRelated: Boolean,
        format: OutputFormat?,
    ): UUID {
        val selected = selected(reportIds)
        if (selected.any { it.pdfSha256 == null }) {
            throw ErikaException("REPORT_PDF_PENDING", HttpStatus.CONFLICT, "A report's PDF is still being made. Try again in a moment.")
        }
        if (lines.any { it.netPrice == null }) {
            throw ErikaException("PRICE_MISSING", HttpStatus.BAD_REQUEST, "Every line needs a net price.")
        }
        val dates = selected.map { it.signedAt.atZoneSameInstant(BERLIN).toLocalDate() }
        val start = dates.min()
        val end = dates.max()
        val id =
            invoices.create(
                workspaceId,
                actorId,
                SourceInvoiceRequest(
                    customerId = selected.first().customerId,
                    sourceModule = SOURCE,
                    sourceIds = selected.map { it.reportId },
                    lines =
                        lines.map {
                            SourceInvoiceLine(
                                name = it.name,
                                quantity = it.quantity,
                                unitCode = it.unitCode,
                                netPrice = it.netPrice!!,
                                vatRate = it.vatRate,
                                description = it.description,
                                articleId = it.articleId,
                                articleNumber = it.articleNumber,
                                laborCost = it.laborCost,
                            )
                        },
                    attachments =
                        selected.map {
                            SourceInvoiceAttachment(
                                sha256 = it.pdfSha256!!,
                                fileName = "Arbeitsbericht-${it.verificationCode}.pdf",
                                mimeCode = "application/pdf",
                                reference = it.verificationCode,
                                description = "Arbeitsbericht ${it.workOrderTitle}".take(100),
                            )
                        },
                    format = format,
                    deliveryDate = start.takeIf { start == end },
                    periodStart = start.takeIf { start != end },
                    periodEnd = end.takeIf { start != end },
                    propertyRelated = propertyRelated,
                    note =
                        "Leistungen laut " + (if (selected.size == 1) "Arbeitsbericht " else "Arbeitsberichten ") +
                            selected.joinToString(", ") { it.verificationCode } + ".",
                ),
            )
        repo.link(workspaceId, id, selected.associate { it.reportId to it.workOrderId }, actorId)
        events.publishEvent(ReportInvoiceRequested(workspaceId, actorId, id, selected.map { it.reportId }))
        return id
    }

    /** In the issuing transaction: the work orders of an invoice from reports are INVOICED. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: InvoiceIssued) {
        if (event.kind != InvoiceKind.INVOICE) return
        repo.workOrdersOf(event.invoiceId).forEach { workOrders.markInvoiced(event.workspaceId, event.actorId, it) }
    }

    private fun selected(reportIds: List<UUID>): List<ReportBilling> {
        val ids = reportIds.distinct()
        val data = reports.billingData(ids)
        if (data.size != ids.size) {
            throw ErikaException(
                "REPORT_NOT_BILLABLE",
                HttpStatus.CONFLICT,
                "Only the current, signed (or unsigned sent) version of a report can be invoiced.",
            )
        }
        val selected = ids.map(data::getValue)
        if (selected.map { it.customerId }.distinct().size > 1) {
            throw ErikaException("MIXED_CUSTOMERS", HttpStatus.BAD_REQUEST, "All reports of an invoice must belong to one customer.")
        }
        if (invoices.invoiceOf(SOURCE, ids).isNotEmpty()) {
            throw ErikaException("ALREADY_INVOICED", HttpStatus.CONFLICT, "A report is already on another invoice.")
        }
        return selected
    }

    private fun describe(r: ReportBilling) =
        "${r.workOrderTitle}, Arbeitsbericht ${r.verificationCode} vom ${DATE.format(r.signedAt.atZoneSameInstant(BERLIN))}".take(1000)

    private fun notFound() = ErikaException("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, "The customer doesn't exist.")

    companion object {
        const val SOURCE = "reports"
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
