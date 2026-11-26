package de.erika.reports.web

import de.erika.archive.Archive
import de.erika.contract.api.ReportVerificationApi
import de.erika.contract.model.ReportVerification
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import org.springframework.context.annotation.Profile
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The public check of a signed report (M-MO-11; api/openapi.yaml, tag `report-verification`):
 * without a session, by the code on the PDF. Shows hash and status, never content; with the
 * download key from the QR code, the customer gets the archived PDF.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ReportVerificationController(
    private val jdbc: JdbcClient,
    private val archive: Archive,
    private val tenant: TenantContext,
    transactionManager: PlatformTransactionManager,
) : ReportVerificationApi {
    // Not read-only: opening an archived file records the check.
    private val tx = TransactionTemplate(transactionManager)

    private data class Found(
        val workspaceId: UUID,
        val kind: String,
        val contentHash: String,
        val signedAt: OffsetDateTime,
        val version: Int,
        val superseded: Boolean,
        val pdfSha256: String?,
        val keyHash: ByteArray,
        val firmName: String,
    )

    private fun find(code: String): Found =
        jdbc
            .sql("SELECT * FROM report_verification(:code)")
            .param("code", code)
            .query { rs, _ ->
                Found(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    kind = rs.getString("kind"),
                    contentHash = rs.getString("content_hash"),
                    signedAt = rs.getObject("signed_at", OffsetDateTime::class.java),
                    version = rs.getInt("report_version"),
                    superseded = rs.getBoolean("superseded"),
                    pdfSha256 = rs.getString("pdf_sha256"),
                    keyHash = rs.getBytes("download_key_hash"),
                    firmName = rs.getString("firm_name"),
                )
            }.optional()
            .orElseThrow { notFound() }

    override fun getReportVerification(code: String): ResponseEntity<ReportVerification> {
        val f = checkNotNull(tx.execute { find(code) })
        return ResponseEntity.ok(
            ReportVerification(
                code = code,
                firmName = f.firmName,
                kind = ReportVerification.Kind.forValue(f.kind),
                reportVersion = f.version,
                signedAt = f.signedAt,
                contentHash = f.contentHash,
                superseded = f.superseded,
                pdfArchived = f.pdfSha256 != null,
            ),
        )
    }

    override fun getVerifiedReportPdf(
        code: String,
        key: String,
    ): ResponseEntity<Resource> {
        val bytes =
            checkNotNull(
                tx.execute {
                    val f = find(code)
                    val given = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII))
                    if (!MessageDigest.isEqual(given, f.keyHash) || f.pdfSha256 == null) throw notFound()
                    // The archive is read in the report's workspace (row-level security).
                    tenant.enterWorkspace(f.workspaceId)
                    archive.open(f.workspaceId, f.pdfSha256)?.bytes ?: throw notFound()
                },
            )
        return pdf(bytes, "Arbeitsbericht-$code.pdf")
    }

    private fun notFound() = ErikaException("REPORT_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
}
