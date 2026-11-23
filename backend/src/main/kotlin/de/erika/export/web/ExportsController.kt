package de.erika.export.web

import de.erika.contract.api.ExportsApi
import de.erika.contract.model.DatevSettings
import de.erika.contract.model.DatevSettingsUpdate
import de.erika.contract.model.Export
import de.erika.contract.model.ExportList
import de.erika.contract.model.ExportRequest
import de.erika.contract.model.Problem
import de.erika.export.DatevChart
import de.erika.export.DatevConfig
import de.erika.export.DatevSettingsChanged
import de.erika.export.DatevSettingsRepository
import de.erika.export.ExportDownloaded
import de.erika.export.ExportKind
import de.erika.export.ExportRepository
import de.erika.export.ExportRow
import de.erika.export.ExportScope
import de.erika.export.ExportState
import de.erika.export.Exports
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import de.erika.platform.web.Idempotency
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import de.erika.contract.model.DatevChart as ApiChart
import de.erika.contract.model.ExportKind as ApiKind
import de.erika.contract.model.ExportScope as ApiScope
import de.erika.contract.model.ExportState as ApiState

/**
 * Exports (EX-01, EX-04). Every role may start and download period exports, tax advisors
 * included; the full account export and the DATEV settings are for owners.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ExportsController(
    private val exports: Exports,
    private val repository: ExportRepository,
    private val datev: DatevSettingsRepository,
    private val contacts: WorkspaceContacts,
    private val storage: ObjectStorage,
    private val storageProps: StorageProperties,
    private val idempotency: Idempotency,
    private val events: ApplicationEventPublisher,
) : ExportsApi {
    override fun listExports(): ResponseEntity<ExportList> {
        val rows = repository.recent(LIST_LIMIT)
        val names = contacts.displayNames(rows.map { it.requestedBy }.toSet())
        return ResponseEntity.ok(ExportList(rows.map { it.toApi(names) }))
    }

    override fun createExport(
        idempotencyKey: String,
        exportRequest: ExportRequest,
    ): ResponseEntity<Export> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createExport") {
                exports.request(
                    p.workspaceId,
                    p.userId,
                    p.role,
                    ExportKind.valueOf(exportRequest.kind.value),
                    exportRequest.from,
                    exportRequest.to,
                    exportRequest.scope?.let { ExportScope.valueOf(it.value) } ?: ExportScope.ALL,
                )
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(load(result.resourceId))
    }

    override fun getExport(exportId: UUID): ResponseEntity<Export> = ResponseEntity.ok(load(exportId))

    /** 303 to a 60-second download URL; the request itself needs the session (EX-01: link only after sign-in). */
    override fun downloadExport(exportId: UUID): ResponseEntity<Problem> {
        val p = ErikaPrincipal.current()
        val export = repository.find(exportId) ?: throw notFound()
        when (export.effectiveState(OffsetDateTime.now())) {
            ExportState.READY -> Unit

            ExportState.EXPIRED -> throw ErikaException(
                "EXPORT_EXPIRED",
                HttpStatus.GONE,
                "This export has expired and was deleted. Start a new one.",
            )

            ExportState.FAILED -> throw ErikaException("EXPORT_FAILED", HttpStatus.CONFLICT, "This export failed. Start a new one.")

            ExportState.QUEUED -> throw ErikaException("EXPORT_NOT_READY", HttpStatus.CONFLICT, "The export is still being built.")
        }
        val url =
            storage.presignDownload(
                storageProps.exportsBucket,
                checkNotNull(export.storageKey),
                export.fileName ?: "export.zip",
                DOWNLOAD_URL_VALIDITY,
            )
        events.publishEvent(ExportDownloaded(p.workspaceId, p.userId, export.id))
        return ResponseEntity
            .status(HttpStatus.SEE_OTHER)
            .location(URI.create(url))
            .cacheControl(CacheControl.noStore())
            .build()
    }

    override fun getDatevSettings(): ResponseEntity<DatevSettings> {
        val stored = datev.current()
        return ResponseEntity.ok().eTag(Etags.of(stored.version)).body(stored.config.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun updateDatevSettings(
        datevSettingsUpdate: DatevSettingsUpdate,
        ifMatch: String?,
    ): ResponseEntity<DatevSettings> {
        val p = ErikaPrincipal.current()
        val expected = Etags.required(ifMatch)
        val config =
            with(datevSettingsUpdate) {
                DatevConfig(
                    consultantNumber = consultantNumber,
                    clientNumber = clientNumber,
                    chart = DatevChart.valueOf(chart.value),
                    fiscalYearStartMonth = fiscalYearStartMonth,
                    accountLength = accountLength,
                    expenseAccount = expenseAccount?.takeIf { it.isNotBlank() },
                    revenueAccount = revenueAccount?.takeIf { it.isNotBlank() },
                    creditorAccount = creditorAccount?.takeIf { it.isNotBlank() },
                    debtorAccount = debtorAccount?.takeIf { it.isNotBlank() },
                )
            }
        val version = datev.save(p.workspaceId, expected, config) ?: throw Etags.conflict()
        events.publishEvent(DatevSettingsChanged(p.workspaceId, p.userId))
        return ResponseEntity.ok().eTag(Etags.of(version)).body(config.toApi())
    }

    private fun load(id: UUID): Export {
        val row = repository.find(id) ?: throw notFound()
        return row.toApi(contacts.displayNames(setOf(row.requestedBy)))
    }

    private fun notFound() = ErikaException("EXPORT_NOT_FOUND", HttpStatus.NOT_FOUND, "Export not found.")

    private fun ExportRow.toApi(names: Map<UUID, String>) =
        Export(
            id = id,
            kind = ApiKind.forValue(kind.name),
            scope = ApiScope.forValue(scope.name),
            state = ApiState.forValue(effectiveState(OffsetDateTime.now()).name),
            createdAt = createdAt,
            from = from,
            to = to,
            finishedAt = finishedAt,
            expiresAt = expiresAt,
            fileName = fileName,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            documentCount = documentCount,
            requestedByName = names[requestedBy],
        )

    private fun DatevConfig.toApi() =
        DatevSettings(
            chart = ApiChart.forValue(chart.name),
            fiscalYearStartMonth = fiscalYearStartMonth,
            accountLength = accountLength,
            expenseAccount = expense,
            revenueAccount = revenue,
            creditorAccount = creditor,
            debtorAccount = debtor,
            complete = complete,
            consultantNumber = consultantNumber,
            clientNumber = clientNumber,
        )

    private companion object {
        const val LIST_LIMIT = 50
        val DOWNLOAD_URL_VALIDITY: Duration = Duration.ofSeconds(60)
    }
}
