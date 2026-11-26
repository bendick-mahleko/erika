package de.erika.reports

import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestInfrastructure
import de.erika.support.TestPhotos
import de.erika.support.TestSyncDevice
import de.erika.support.TestTus
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A firm with a technician's phone, a work order and helpers to record and sign its report the way
 * the app does (MARA Stage 7); shared by the signature and invoicing tests.
 */
abstract class SignedReportTest : ApiIntegrationTest() {
    protected val phones by lazy { Phones(this) }

    protected fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    protected fun id() = UUID.randomUUID().toString()

    protected data class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val device: TestSyncDevice,
        val order: String,
    ) {
        fun report(version: Int = 1) = DerivedIds.report(UUID.fromString(order), version).toString()
    }

    protected fun firm(
        prefix: String,
        customerType: String = "BUSINESS",
    ): Firm {
        val owner = client()
        val ownerUser = accounts.enrolled(prefix, owner, workspaceName = "Sanitär $prefix")
        val (phone, tech) = phones.signIn(phones.technician(owner, "$prefix-tech"))
        val customer =
            owner
                .post(
                    "/api/v1/customers",
                    mapOf(
                        "name" to "Familie Sommer",
                        "customerType" to customerType,
                        "street" to "Lindenweg 3",
                        "postalCode" to "10115",
                        "city" to "Berlin",
                        "countryCode" to "DE",
                        "vatTreatment" to "DOMESTIC",
                        "email" to "sommer-$prefix@example.test",
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val template =
            owner
                .post(
                    "/api/v1/checklist-templates",
                    mapOf(
                        "name" to "Wartung",
                        "items" to listOf(mapOf("key" to "druck", "label" to "Druck (bar)", "kind" to "VALUE", "required" to true)),
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
        return Firm(owner, ownerUser, tech, device, order)
    }

    protected fun TestSyncDevice.pushResults(): List<JsonNode> =
        push()
            .tree
            .path("results")
            .values()
            .toList()

    protected fun now(minusMinutes: Long = 0) =
        Instant
            .now()
            .minus(minusMinutes, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()

    /** Records a small report with a stored photo; returns the photo's ID. */
    protected fun record(
        f: Firm,
        report: String = f.report(),
    ): String {
        f.device.create("report", report, mapOf("workOrderId" to f.order, "workDescription" to "Brenner gereinigt, Druck geprüft."))
        f.device.create(
            "time_entry",
            id(),
            mapOf("reportId" to report, "startedAt" to now(95), "endedAt" to now(10), "note" to "mit Azubi"),
        )
        f.device.create(
            "material_line",
            id(),
            mapOf(
                "reportId" to report,
                "description" to "Dichtung",
                "quantity" to "2.000",
                "unitCode" to "C62",
            ),
        )
        f.device.create(
            "aufmass_position",
            id(),
            mapOf("reportId" to report, "label" to "Wand", "kind" to "AREA", "length" to "5.40", "width" to "2.75", "unitCode" to "MTK"),
        )
        f.device.create(
            "checklist_answer",
            DerivedIds.checklistAnswer(UUID.fromString(report), "druck").toString(),
            mapOf("reportId" to report, "itemKey" to "druck", "value" to "1,8"),
        )
        val jpeg = TestPhotos.jpeg(640, 480, seed = 9)
        val photo = id()
        f.device.create("photo", photo, TestPhotos.record(report, jpeg))
        assertTrue(f.device.pushResults().all { it.path("status").asString() == "APPLIED" })
        assertEquals(204, TestTus(f.device.phone).upload(photo, jpeg, 64 * 1024))
        WorkerContext.drain()
        f.device.pullAll()
        return photo
    }

    /** The canonical report input built from the phone's records, as the app does. */
    protected fun phoneRaw(
        f: Firm,
        report: String,
    ): Map<String, Any?> {
        val d = f.device
        val r = d.record("report", report)!!.data
        val wo = d.record("work_order", f.order)!!.data
        val names = d.all("staff_member").associate { it.id to it.data["displayName"] }
        val items = d.record("checklist_template", wo["checklistTemplateId"] as String)!!.data["items"] as List<*>
        val mine = { entity: String -> d.all(entity).filter { it.data["reportId"] == report } }
        val answers = mine("checklist_answer").associate { it.data["itemKey"] to it.data["value"] }
        return mapOf(
            "format" to 1,
            "reportId" to report,
            "version" to r["version"],
            "workOrder" to mapOf("id" to f.order, "title" to wo["title"]),
            "customer" to mapOf("name" to d.record("customer", wo["customerId"] as String)!!.data["name"]),
            "workDescription" to r["workDescription"],
            "times" to
                mine("time_entry").map {
                    mapOf(
                        "id" to it.id,
                        "technician" to names[it.data["technicianId"]],
                        "kind" to it.data["kind"],
                        "start" to it.data["startedAt"],
                        "end" to it.data["endedAt"],
                        "note" to it.data["note"],
                    )
                },
            "materials" to
                mine("material_line").map {
                    mapOf(
                        "id" to it.id,
                        "description" to it.data["description"],
                        "quantity" to it.data["quantity"],
                        "unit" to it.data["unitCode"],
                    )
                },
            "aufmass" to
                mine("aufmass_position").map {
                    it.data.filterKeys { k ->
                        k in
                            setOf("label", "kind", "length", "width", "height", "count", "expression", "deductions", "result")
                    } +
                        mapOf("id" to it.id, "unit" to it.data["unitCode"])
                },
            "checklist" to
                items.map { it as Map<*, *> }.filter { it["kind"] != "PHOTO" }.map {
                    mapOf("key" to it["key"], "label" to it["label"], "value" to answers[it["key"]])
                },
            "photos" to
                mine("photo").map {
                    mapOf(
                        "id" to it.id,
                        "category" to it.data["category"],
                        "caption" to it.data["caption"],
                        "checklistItemKey" to it.data["checklistItemKey"],
                        "sha256" to it.data["sha256"],
                    )
                },
        )
    }

    protected fun png(): String {
        val image = BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = java.awt.Color.BLACK
            drawLine(10, 80, 290, 20)
            dispose()
        }
        return Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
    }

    protected fun code() = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".random() }.joinToString("")

    protected val downloadKey = "k" + UUID.randomUUID().toString().replace("-", "") + "x"

    protected fun sign(
        f: Firm,
        report: String,
        hash: String = ReportCanonical.hash(phoneRaw(f, report)),
        code: String = code(),
        kind: String = "SIGNED",
    ): JsonNode {
        f.device.create(
            "report_signature",
            DerivedIds.of(UUID.fromString(report), "signature").toString(),
            mapOf(
                "reportId" to report,
                "kind" to kind,
                "contentHash" to hash,
                "signerName" to if (kind == "SIGNED") "Maria Sommer" else null,
                "signedAt" to now(),
                "comment" to "Alles sauber",
                "notSignedReason" to if (kind == "NOT_SIGNED") "Kundin nicht anwesend" else null,
                "strokes" to listOf(listOf(listOf(10, 80), listOf(290, 20))),
                "signaturePng" to if (kind == "SIGNED") png() else null,
                "verificationCode" to code,
                "downloadKey" to downloadKey,
                "customerEmail" to "sommer@example.test",
            ),
        )
        return f.device.pushResults().single()
    }
}
