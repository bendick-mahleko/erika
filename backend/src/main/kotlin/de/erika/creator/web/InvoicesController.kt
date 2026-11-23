package de.erika.creator.web

import de.erika.archive.Archive
import de.erika.contract.api.InvoicesApi
import de.erika.contract.model.BuyerData
import de.erika.contract.model.CreditNoteRequest
import de.erika.contract.model.DeliveryStatus
import de.erika.contract.model.DocumentStatus
import de.erika.contract.model.DraftProblem
import de.erika.contract.model.InvoiceContent
import de.erika.contract.model.InvoiceDelivery
import de.erika.contract.model.InvoiceFormat
import de.erika.contract.model.InvoiceKind
import de.erika.contract.model.InvoiceLineInput
import de.erika.contract.model.InvoiceList
import de.erika.contract.model.InvoiceListItem
import de.erika.contract.model.InvoiceReference
import de.erika.contract.model.InvoiceSettings
import de.erika.contract.model.InvoiceSettingsUpdate
import de.erika.contract.model.InvoiceState
import de.erika.contract.model.InvoiceTotals
import de.erika.contract.model.IssuedInfo
import de.erika.contract.model.MessageSeverity
import de.erika.contract.model.SellerProfile
import de.erika.contract.model.SendInvoiceRequest
import de.erika.contract.model.ValidationMessage
import de.erika.contract.model.VatAmount
import de.erika.contract.model.VatRate
import de.erika.creator.BuyerSnapshot
import de.erika.creator.Creator
import de.erika.creator.DeliveryRow
import de.erika.creator.DraftContent
import de.erika.creator.DraftLine
import de.erika.creator.DraftState
import de.erika.creator.InvoiceDetails
import de.erika.creator.IssuedRow
import de.erika.creator.LineVatRate
import de.erika.creator.VatTreatment
import de.erika.invoicecore.write.OutputFormat
import de.erika.invoicecore.write.VatCase
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import de.erika.platform.web.Idempotency
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import de.erika.contract.model.Invoice as ApiInvoice
import de.erika.contract.model.VatTreatment as ApiVatTreatment
import de.erika.creator.InvoiceKind as Kind
import de.erika.creator.InvoiceSettings as Settings

/**
 * Invoice creator API (CR-01 … CR-07). Runs in the tenant transaction; another workspace's
 * invoice is indistinguishable from a missing one (404). Reading for every role; changes
 * for OWNER and MEMBER (settings: OWNER).
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class InvoicesController(
    private val creator: Creator,
    private val idempotency: Idempotency,
    private val archive: Archive,
) : InvoicesApi {
    // --- settings ---------------------------------------------------------------------

    override fun getInvoiceSettings(): ResponseEntity<InvoiceSettings> {
        val ws = ErikaPrincipal.current().workspaceId
        val s = creator.settings(ws)
        return ResponseEntity.ok().eTag(Etags.of(s.version)).body(settingsToApi(ws, s))
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun updateInvoiceSettings(
        invoiceSettingsUpdate: InvoiceSettingsUpdate,
        ifMatch: String?,
    ): ResponseEntity<InvoiceSettings> {
        val p = ErikaPrincipal.current()
        val expected = Etags.required(ifMatch)
        val u = invoiceSettingsUpdate
        val saved =
            creator.updateSettings(
                p.workspaceId,
                p.userId,
                expected,
                Settings(
                    contactName = u.contactName.clean(),
                    contactPhone = u.contactPhone.clean(),
                    contactEmail = u.contactEmail.clean(),
                    legalRegistration = u.legalRegistration.clean(),
                    iban =
                        u.iban
                            .clean()
                            ?.replace(" ", "")
                            ?.uppercase(),
                    bic =
                        u.bic
                            .clean()
                            ?.replace(" ", "")
                            ?.uppercase(),
                    accountHolder = u.accountHolder.clean(),
                    kleinunternehmer = u.kleinunternehmer,
                    numberPattern = u.numberPattern.trim(),
                    creditNotePattern = u.creditNotePattern.trim(),
                    defaultFormat = OutputFormat.valueOf(u.defaultFormat.value),
                    paymentTermsDays = u.paymentTermsDays,
                ),
            )
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(settingsToApi(p.workspaceId, saved))
    }

    private fun settingsToApi(
        ws: UUID,
        s: Settings,
    ): InvoiceSettings {
        val seller = creator.seller(ws)
        return InvoiceSettings(
            kleinunternehmer = s.kleinunternehmer,
            numberPattern = s.numberPattern,
            creditNotePattern = s.creditNotePattern,
            defaultFormat = InvoiceFormat.forValue(s.defaultFormat.name),
            paymentTermsDays = s.paymentTermsDays,
            seller =
                SellerProfile(
                    name = seller.name,
                    countryCode = seller.countryCode,
                    street = seller.street,
                    postalCode = seller.postalCode,
                    city = seller.city,
                    vatId = seller.vatId,
                    taxNumber = seller.taxNumber,
                ),
            missing = creator.missing(s, seller),
            nextNumber = creator.nextNumber(ws, s),
            version = s.version,
            contactName = s.contactName,
            contactPhone = s.contactPhone,
            contactEmail = s.contactEmail,
            legalRegistration = s.legalRegistration,
            iban = s.iban,
            bic = s.bic,
            accountHolder = s.accountHolder,
        )
    }

    // --- drafts -----------------------------------------------------------------------

    override fun listInvoices(
        state: InvoiceState?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<InvoiceList> {
        val (page, statuses) = creator.list(state?.let { DraftState.valueOf(it.value) }, limit + 1, cursor?.let(::decodeCursor))
        val items = page.take(limit)
        return ResponseEntity.ok(
            InvoiceList(
                items =
                    items.map { (draft, issued) ->
                        InvoiceListItem(
                            id = draft.id,
                            kind = InvoiceKind.forValue(draft.kind.name),
                            state = InvoiceState.forValue(draft.state.name),
                            updatedAt = draft.updatedAt,
                            number = issued?.number,
                            buyerName = issued?.buyerName ?: draft.content.buyer.name,
                            issueDate = issued?.issueDate,
                            dueDate = issued?.dueDate,
                            grossAmount = issued?.gross?.toPlainString(),
                            currency = issued?.currency,
                            status = issued?.let { statuses[it.documentId] }?.let { DocumentStatus.forValue(it.name) },
                        )
                    },
                nextCursor = if (page.size > limit) encodeCursor(items.last().draft.updatedAt, items.last().draft.id) else null,
            ),
        )
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createInvoiceDraft(
        idempotencyKey: String,
        invoiceContent: InvoiceContent,
    ): ResponseEntity<ApiInvoice> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createInvoiceDraft") {
                creator.createDraft(p.workspaceId, p.userId, invoiceContent.toModel())
            }
        return respond(result.resourceId, HttpStatus.CREATED)
    }

    override fun getInvoice(invoiceId: UUID): ResponseEntity<ApiInvoice> = respond(invoiceId, HttpStatus.OK)

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateInvoiceDraft(
        invoiceId: UUID,
        invoiceContent: InvoiceContent,
        ifMatch: String?,
    ): ResponseEntity<ApiInvoice> {
        creator.updateDraft(invoiceId, ifMatch, invoiceContent.toModel())
        return respond(invoiceId, HttpStatus.OK)
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun deleteInvoiceDraft(invoiceId: UUID): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        creator.deleteDraft(p.workspaceId, p.userId, invoiceId)
        return ResponseEntity.noContent().build()
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun issueInvoice(
        invoiceId: UUID,
        idempotencyKey: String,
    ): ResponseEntity<ApiInvoice> {
        val p = ErikaPrincipal.current()
        idempotency.once(p.workspaceId, idempotencyKey, "issueInvoice:$invoiceId") {
            creator.requestIssue(p.workspaceId, p.userId, invoiceId)
            invoiceId
        }
        return respond(invoiceId, HttpStatus.ACCEPTED)
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createCreditNote(
        invoiceId: UUID,
        idempotencyKey: String,
        creditNoteRequest: CreditNoteRequest,
    ): ResponseEntity<ApiInvoice> {
        val p = ErikaPrincipal.current()
        creator.issued(invoiceId) ?: throw if (creator.details(invoiceId) != null) Creator.notIssued() else Creator.notFound()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createCreditNote:$invoiceId") {
                creator.createCorrection(p.workspaceId, p.userId, invoiceId, Kind.valueOf(creditNoteRequest.kind.value))
            }
        return respond(result.resourceId, HttpStatus.CREATED)
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun sendInvoice(
        invoiceId: UUID,
        idempotencyKey: String,
        sendInvoiceRequest: SendInvoiceRequest,
    ): ResponseEntity<InvoiceDelivery> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "sendInvoice:$invoiceId") {
                creator.requestSend(
                    p.workspaceId,
                    p.userId,
                    invoiceId,
                    sendInvoiceRequest.to,
                    sendInvoiceRequest.subject,
                    sendInvoiceRequest.message,
                )
            }
        val delivery = creator.delivery(invoiceId, result.resourceId) ?: throw Creator.notFound()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(delivery.toApi())
    }

    /** CR-07: the sent message as archived, hash-checked; always a download. */
    override fun getInvoiceDeliveryMessage(
        invoiceId: UUID,
        deliveryId: UUID,
    ): ResponseEntity<Resource> {
        val delivery = creator.delivery(invoiceId, deliveryId) ?: throw deliveryNotFound()
        val sha = delivery.emlSha256 ?: throw deliveryNotFound()
        val content = archive.open(delivery.workspaceId, sha) ?: throw deliveryNotFound()
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType("message/rfc822")
        headers.contentDisposition = ContentDisposition.attachment().filename("email-${delivery.id}.eml", StandardCharsets.UTF_8).build()
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox")
        headers.cacheControl = CacheControl.noStore().cachePrivate().headerValue
        return ResponseEntity.ok().headers(headers).body(ByteArrayResource(content.bytes))
    }

    // --- mapping ----------------------------------------------------------------------

    private fun respond(
        id: UUID,
        status: HttpStatus,
    ): ResponseEntity<ApiInvoice> {
        val d = creator.details(id) ?: throw Creator.notFound()
        return ResponseEntity.status(status).eTag(Etags.of(d.draft.version)).body(d.toApi())
    }

    private fun InvoiceDetails.toApi(): ApiInvoice {
        val p = preview
        return ApiInvoice(
            id = draft.id,
            kind = InvoiceKind.forValue(draft.kind.name),
            state = InvoiceState.forValue(draft.state.name),
            version = draft.version,
            content = draft.content.toApi(),
            problems = problems.map { DraftProblem(field = it.field, code = it.code) },
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt,
            totals =
                p?.let {
                    InvoiceTotals(
                        currency = it.currency ?: "EUR",
                        netAmount = it.totals.totalWithoutVat!!.toPlainString(),
                        taxAmount = it.totals.totalVat!!.toPlainString(),
                        grossAmount = it.totals.totalWithVat!!.toPlainString(),
                        lineAmounts = it.lines.map { l -> l.netAmount!!.toPlainString() },
                        vat =
                            it.vatBreakdown.map { v ->
                                VatAmount(
                                    category = v.categoryCode!!,
                                    rate = v.rate!!.toPlainString(),
                                    taxableAmount = v.taxableAmount!!.toPlainString(),
                                    taxAmount = v.taxAmount!!.toPlainString(),
                                    note = VatCase.of(v.categoryCode, v.rate)?.note,
                                )
                            },
                    )
                },
            issueError =
                draft.issueError?.map { m ->
                    ValidationMessage(
                        ruleId = m.ruleId,
                        severity = MessageSeverity.forValue(m.severity.name),
                        source = ValidationMessage.Source.forValue(m.source.name),
                        text = m.text,
                        originalText = m.originalText,
                        location = m.location,
                    )
                },
            original = original?.ref(),
            corrections = corrections.map { it.ref() },
            issued =
                issued?.let {
                    IssuedInfo(
                        number = it.number,
                        issueDate = it.issueDate,
                        documentId = it.documentId,
                        status = DocumentStatus.forValue((status ?: de.erika.documents.DocumentStatus.ISSUED).name),
                        issuedAt = it.issuedAt,
                        fileName = it.fileName,
                        dueDate = it.dueDate,
                    )
                },
            deliveries = deliveries.map { it.toApi() },
        )
    }

    private fun IssuedRow.ref() = InvoiceReference(id = id, number = number, kind = InvoiceKind.forValue(kind.name))

    private fun DeliveryRow.toApi() =
        InvoiceDelivery(
            id = id,
            to = recipient,
            subject = subject,
            status = DeliveryStatus.forValue(status.name),
            createdAt = createdAt,
            detail = detail,
            sentAt = sentAt,
            archived = emlSha256 != null,
        )

    private fun DraftContent.toApi() =
        InvoiceContent(
            format = format?.let { InvoiceFormat.forValue(it.name) },
            customerId = customerId,
            buyer =
                BuyerData(
                    customerNumber = buyer.customerNumber,
                    name = buyer.name,
                    street = buyer.street,
                    additionalLine = buyer.additionalLine,
                    postalCode = buyer.postalCode,
                    city = buyer.city,
                    countryCode = buyer.countryCode,
                    vatId = buyer.vatId,
                    email = buyer.email,
                    contactName = buyer.contactName,
                ),
            vatTreatment = vatTreatment?.let { ApiVatTreatment.forValue(it.name) },
            issueDate = issueDate,
            deliveryDate = deliveryDate,
            periodStart = periodStart,
            periodEnd = periodEnd,
            dueDate = dueDate,
            buyerReference = buyerReference,
            orderReference = orderReference,
            paymentTerms = paymentTerms,
            note = note,
            lines =
                lines.map {
                    InvoiceLineInput(
                        articleId = it.articleId,
                        articleNumber = it.articleNumber,
                        name = it.name,
                        description = it.description,
                        quantity = it.quantity?.toPlainString(),
                        unitCode = it.unitCode,
                        netPrice = it.netPrice?.toPlainString(),
                        vatRate = it.vatRate?.let { r -> VatRate.forValue(r.name) },
                    )
                },
        )

    private fun InvoiceContent.toModel() =
        DraftContent(
            format = format?.let { OutputFormat.valueOf(it.value) },
            customerId = customerId,
            buyer =
                buyer?.let { b ->
                    BuyerSnapshot(
                        customerNumber = b.customerNumber.clean(),
                        name = b.name.clean(),
                        street = b.street.clean(),
                        additionalLine = b.additionalLine.clean(),
                        postalCode = b.postalCode.clean(),
                        city = b.city.clean(),
                        countryCode = b.countryCode.clean(),
                        vatId = b.vatId.clean(),
                        email = b.email.clean(),
                        contactName = b.contactName.clean(),
                    )
                } ?: BuyerSnapshot(),
            vatTreatment = vatTreatment?.let { VatTreatment.valueOf(it.value) },
            issueDate = issueDate,
            deliveryDate = deliveryDate,
            periodStart = periodStart,
            periodEnd = periodEnd,
            dueDate = dueDate,
            buyerReference = buyerReference.clean(),
            orderReference = orderReference.clean(),
            paymentTerms = paymentTerms.clean(),
            note = note.clean(),
            lines =
                lines.orEmpty().map {
                    DraftLine(
                        articleId = it.articleId,
                        articleNumber = it.articleNumber.clean(),
                        name = it.name.clean(),
                        description = it.description.clean(),
                        quantity = it.quantity?.trim()?.toBigDecimalOrNull(),
                        unitCode = it.unitCode.clean(),
                        netPrice = it.netPrice?.trim()?.toBigDecimalOrNull(),
                        vatRate = it.vatRate?.let { r -> LineVatRate.valueOf(r.value) },
                    )
                },
        )

    private fun deliveryNotFound() = ErikaException("DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND, "Delivery not found.")

    private fun encodeCursor(
        at: OffsetDateTime,
        id: UUID,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> =
        try {
            val (at, id) = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split('|', limit = 2)
            OffsetDateTime.parse(at) to UUID.fromString(id)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            throw ErikaException("INVALID_CURSOR", HttpStatus.BAD_REQUEST, "The cursor is not valid.", e)
        }
}
