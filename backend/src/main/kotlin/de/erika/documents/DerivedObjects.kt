package de.erika.documents

import de.erika.archive.Archive
import de.erika.invoicecore.model.Attachment
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** Bytes of a derived object, checked against the SHA-256 recorded when it was written. */
internal class DerivedContent(
    val row: DerivedRow,
    val bytes: ByteArray,
)

/**
 * Objects rebuilt from an original (ADR 0025): the PDF view copy (VW-02) and the decoded
 * embedded attachments (VW-06). They live in the derived bucket (no Object Lock) under
 * `ws/<workspace>/<document>/…`, with a row in `derived_object`. Writing again overwrites,
 * so the parse job stays idempotent.
 */
@Component
internal class DerivedObjects(
    private val repository: DocumentRepository,
    private val storage: ObjectStorage,
    private val props: StorageProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun storeViewPdf(
        workspaceId: UUID,
        documentId: UUID,
        pdf: ByteArray,
        title: String,
    ) = store(workspaceId, documentId, VIEW_PDF, 0, pdf, "application/pdf", "Ansichtskopie ${safe(title)}.pdf")

    fun storeAttachment(
        workspaceId: UUID,
        documentId: UUID,
        index: Int,
        attachment: Attachment,
    ) = store(
        workspaceId,
        documentId,
        ATTACHMENT,
        index,
        attachment.content,
        attachment.mimeCode?.takeIf { MEDIA_TYPE.matches(it) } ?: "application/octet-stream",
        attachment.filename?.let(::safe)?.takeIf { it.isNotBlank() } ?: "anhang-${index + 1}",
    )

    /** Null if missing or damaged (logged); derived objects can be rebuilt by revalidating. */
    fun read(
        documentId: UUID,
        kind: String,
        index: Int,
    ): DerivedContent? {
        val row = repository.derived(documentId, kind, index) ?: return null
        val bytes =
            storage.get(props.derivedBucket, row.storageKey) ?: return null.also { log.warn("Derived object {} missing", row.storageKey) }
        if (Archive.sha256Hex(bytes) != row.sha256) {
            log.warn("Derived object {} doesn't match its SHA-256; revalidate the document to rebuild it", row.storageKey)
            return null
        }
        return DerivedContent(row, bytes)
    }

    private fun store(
        workspaceId: UUID,
        documentId: UUID,
        kind: String,
        index: Int,
        bytes: ByteArray,
        mediaType: String,
        fileName: String,
    ) {
        val key = "ws/$workspaceId/$documentId/${kind.lowercase()}/$index"
        storage.put(props.derivedBucket, key, bytes, mediaType)
        repository.upsertDerived(
            workspaceId,
            documentId,
            kind,
            index,
            DerivedRow(key, Archive.sha256Hex(bytes), mediaType, fileName, bytes.size.toLong()),
        )
    }

    companion object {
        const val VIEW_PDF = "VIEW_PDF"
        const val ATTACHMENT = "ATTACHMENT"
        private val MEDIA_TYPE = Regex("^[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,62}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}$")

        /** A file name safe for Content-Disposition: no path, no control or quote characters. */
        fun safe(name: String): String =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() || it == '"' }
                .trim()
                .take(200)
    }
}
