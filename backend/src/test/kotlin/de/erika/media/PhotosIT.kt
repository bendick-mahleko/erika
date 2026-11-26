package de.erika.media

import de.erika.reports.DerivedIds
import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestPhotos
import de.erika.support.TestSyncDevice
import de.erika.support.TestTus
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MARA Stage 6: photos of a report (M-MO-05, ADR 0040). The record syncs at once, the file
 * follows through tus, the worker scans it and makes a thumbnail; the office sees it.
 */
class PhotosIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    private data class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val device: TestSyncDevice,
        val tus: TestTus,
        val order: String,
        val report: String,
    )

    /** A firm with a technician, a work order with a mandatory photo item and its report on the phone. */
    private fun firm(prefix: String): Firm {
        val owner = client()
        val ownerUser = accounts.enrolled(prefix, owner, workspaceName = "Firma $prefix")
        val (phone, tech) = phones.signIn(phones.technician(owner, "$prefix-tech"))
        val customer =
            owner
                .post("/api/v1/customers", mapOf("name" to "Familie Roth", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
                .tree
                .path("id")
                .asString()
        val template =
            owner
                .post(
                    "/api/v1/checklist-templates",
                    mapOf(
                        "name" to "Wartung",
                        "items" to
                            listOf(mapOf("key" to "typenschild", "label" to "Foto Typenschild", "kind" to "PHOTO", "required" to true)),
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val order =
            owner
                .post(
                    "/api/v1/work-orders",
                    mapOf(
                        "customerId" to customer,
                        "title" to "Therme warten",
                        "assigneeIds" to listOf(tech.userId),
                        "checklistTemplateId" to template,
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val device = TestSyncDevice(phone)
        device.pullAll()
        val report = DerivedIds.report(UUID.fromString(order)).toString()
        device.create("report", report, mapOf("workOrderId" to order))
        check(device.pushResults().all { it.path("status").asString() == "APPLIED" })
        return Firm(owner, ownerUser, tech, device, TestTus(phone), order, report)
    }

    private fun TestSyncDevice.pushResults(): List<JsonNode> =
        push()
            .tree
            .path("results")
            .values()
            .toList()

    /** Creates the photo record through sync; returns its ID. */
    private fun record(
        f: Firm,
        bytes: ByteArray,
        sha256: String = TestPhotos.sha256(bytes),
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val id = UUID.randomUUID().toString()
        f.device.create("photo", id, TestPhotos.record(f.report, bytes, sha256, extra))
        val result = f.device.pushResults().single()
        assertEquals("APPLIED", result.path("status").asString(), "$result")
        assertEquals(
            "PENDING_FILE",
            result
                .path("record")
                .path("data")
                .path("state")
                .asString(),
        )
        return id
    }

    private fun state(id: String) =
        TestDatabase.strings("SELECT state || coalesce(':' || reject_reason, '') FROM photo WHERE id = ?::uuid", id).single()

    @Test
    fun `the record syncs at once, the file follows in chunks, the worker scans it and the office sees it`() {
        val f = firm("foto")
        f.device.change("report", f.report, mapOf("state" to "COMPLETED"))
        assertEquals(
            "CHECKLIST_INCOMPLETE",
            f.device
                .pushResults()
                .single()
                .path("code")
                .asString(),
            "the photo item is mandatory",
        )

        val jpeg = TestPhotos.jpeg(1600, 1200)
        assertTrue(jpeg.size > 3 * 64 * 1024, "several chunks: ${jpeg.size}")
        val id = record(f, jpeg, extra = mapOf("checklistItemKey" to "typenschild"))
        // A photo for the item counts at once, even before its file arrives (offline on site).
        f.device.change("report", f.report, mapOf("state" to "COMPLETED"))
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()
                .single()
                .path("status")
                .asString(),
        )

        val head = f.tus.head(id)
        assertEquals(200, head.status, "$head")
        assertEquals("0", head.header("Upload-Offset"))
        assertEquals(jpeg.size.toString(), head.header("Upload-Length"))
        assertEquals("1.0.0", head.header("Tus-Resumable"))

        // First chunk, then one sent at a wrong offset (a lost answer), then resume from HEAD.
        val chunk = 64 * 1024
        assertEquals(204, f.tus.patch(id, 0, jpeg.copyOfRange(0, chunk)).status)
        val wrong = f.tus.patch(id, 0, jpeg.copyOfRange(0, chunk))
        assertEquals(409, wrong.status)
        assertEquals("UPLOAD_OFFSET_MISMATCH", wrong.code)
        assertEquals(chunk.toLong(), f.tus.offset(id))
        assertEquals(204, f.tus.upload(id, jpeg, chunk))
        assertEquals("UPLOADED", state(id))
        // The last chunk again (its answer was lost): nothing changes.
        val again = f.tus.patch(id, (jpeg.size - 10).toLong(), jpeg.copyOfRange(jpeg.size - 10, jpeg.size))
        assertEquals(204, again.status)
        assertEquals(jpeg.size.toString(), again.header("Upload-Offset"))

        WorkerContext.drain()
        assertEquals("STORED", state(id))
        f.device.pullAll()
        val local = assertNotNull(f.device.record("photo", id))
        assertEquals("STORED", local.data["state"])
        assertEquals(1600, local.data["width"])

        val report = f.owner.get("/api/v1/work-orders/${f.order}/report").tree
        val photo = report.path("photos").single()
        assertEquals("STORED", photo.path("state").asString())
        assertEquals("typenschild", photo.path("checklistItemKey").asString())
        assertEquals("Vorher", photo.path("caption").asString())
        val thumbnail = f.owner.getBytes("/api/v1/photos/$id/thumbnail")
        assertEquals(200, thumbnail.first)
        val small = ImageIO.read(ByteArrayInputStream(thumbnail.second))
        assertEquals(400, maxOf(small.width, small.height))
        val file = f.owner.get("/api/v1/photos/$id/file")
        assertEquals(200, file.status)
        assertTrue(file.header("Content-Disposition")!!.startsWith("attachment"))
        assertEquals("nosniff", file.header("X-Content-Type-Options"))
        assertTrue(
            TestDatabase
                .strings("SELECT action FROM audit_log WHERE payload->>'id' = ? ORDER BY seq", id)
                .containsAll(listOf("media.photo.created", "media.photo.uploaded", "media.photo.stored")),
        )
    }

    @Test
    fun `a file that doesn't match its hash is refused, and uploaded again it is stored`() {
        val f = firm("foto-hash")
        val jpeg = TestPhotos.jpeg(800, 600, seed = 2)
        val id = record(f, jpeg)
        val damaged = jpeg.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals(460, f.tus.upload(id, damaged, 64 * 1024))
        assertEquals(0, f.tus.offset(id), "the parts are gone; the app starts again")
        assertEquals("PENDING_FILE", state(id))
        assertEquals(204, f.tus.upload(id, jpeg, 64 * 1024))
        WorkerContext.drain()
        assertEquals("STORED", state(id))
    }

    @Test
    fun `the EICAR test file and a file that isn't a JPEG are rejected`() {
        val f = firm("foto-virus")
        val eicar = Samples.eicar()
        val virus = record(f, eicar)
        assertEquals(204, f.tus.upload(virus, eicar))
        val text = "kein Bild, nur Text".repeat(50).toByteArray()
        val notImage = record(f, text)
        assertEquals(204, f.tus.upload(notImage, text))
        WorkerContext.drain()
        assertEquals("REJECTED:VIRUS", state(virus))
        assertEquals("REJECTED:NOT_AN_IMAGE", state(notImage))
        assertEquals(404, f.owner.get("/api/v1/photos/$virus/thumbnail").status)
        f.device.pullAll()
        assertEquals("VIRUS", f.device.record("photo", virus)!!.data["rejectReason"])
    }

    @Test
    fun `uploads follow the rules for size, scope, deleted photos and the tus version`() {
        val f = firm("foto-regeln")
        val jpeg = TestPhotos.jpeg(400, 300, seed = 3)
        val id = record(f, jpeg)
        assertEquals(413, f.tus.patch(id, 0, ByteArray(2 * 1024 * 1024 + 1)).status, "chunks are at most 2 MB")
        assertEquals(413, f.tus.patch(id, 0, ByteArray(jpeg.size + 1)).status, "not past the announced size")
        val oldVersion =
            f.device.phone.send("HEAD", f.tus.path(id), null, arrayOf("Tus-Resumable" to "0.2.2"))
        assertEquals(412, oldVersion.status)
        val json =
            f.device.phone.send(
                "PATCH",
                f.tus.path(id),
                mapOf("x" to 1),
                arrayOf("Tus-Resumable" to "1.0.0", "Upload-Offset" to "0"),
            )
        assertEquals(415, json.status)

        // A technician not on the work order doesn't see the photo.
        val (other, _) = phones.signIn(phones.technician(f.owner, "fremd"))
        assertEquals(404, TestTus(other).head(id).status)

        // Too large a photo can't be announced.
        f.device.create("photo", UUID.randomUUID().toString(), TestPhotos.record(f.report, jpeg) + ("sizeBytes" to 16L * 1024 * 1024))
        assertEquals(
            "PHOTO_TOO_LARGE",
            f.device
                .pushResults()
                .single()
                .path("code")
                .asString(),
        )

        // A deleted photo takes no more chunks.
        f.device.pullAll()
        f.device.delete("photo", id)
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()
                .single()
                .path("status")
                .asString(),
        )
        assertEquals(404, f.tus.head(id).status)
    }
}
