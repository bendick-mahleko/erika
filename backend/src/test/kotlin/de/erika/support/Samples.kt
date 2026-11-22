package de.erika.support

import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test files for intake: official XRechnung samples, ZIPs, emails, malicious files. */
object Samples {
    private val fixtures = File(System.getProperty("erika.test-fixtures"))

    /** Official XRechnung 3.0.2 test file 01.01a (UBL), valid. */
    val xrechnungUbl: String by lazy { fixtures.resolve("xrechnung/instances/standard/01.01a-INVOICE_ubl.xml").readText() }

    /** The same invoice with another invoice number (BT-1): a different invoice. */
    fun xrechnung(invoiceNumber: String): ByteArray =
        xrechnungUbl.replaceFirst("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>$invoiceNumber</cbc:ID>").toByteArray()

    /**
     * The same invoice (number, seller VAT ID, gross amount) with an extra note: other bytes,
     * same business data, i.e. a business duplicate (IN-05).
     */
    fun xrechnungResent(invoiceNumber: String): ByteArray =
        xrechnung(invoiceNumber)
            .decodeToString()
            .replaceFirst("<cbc:DocumentCurrencyCode>", "<cbc:Note>Erneut gesendet</cbc:Note><cbc:DocumentCurrencyCode>")
            .toByteArray()

    /** A one-page PDF without embedded XML: "not an e-invoice (other invoice)" (IN-04). */
    fun plainPdf(): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            // A different file each time (otherwise it is "already in the archive").
            doc.documentInformation.title = "Scan ${java.util.UUID.randomUUID()}"
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    /** 30 MB of zeros compressed to a few KB: a zip bomb (ratio far above 100). */
    fun zipBomb(): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("rechnung.xml"))
                    val zeros = ByteArray(1024 * 1024)
                    repeat(30) { zip.write(zeros) }
                    zip.closeEntry()
                }
            }.toByteArray()

    /**
     * The EICAR anti-virus test file. Assembled at run time, so no file in the repository
     * contains it (local virus scanners would quarantine the source otherwise).
     */
    fun eicar(): ByteArray =
        listOf("X5O!P%@AP[4\\PZX54(P^)7CC)7}", "\$EICAR-STANDARD-", "ANTIVIRUS-TEST-FILE!", "\$H+H*")
            .joinToString("")
            .toByteArray(Charsets.US_ASCII)

    /** A raw email (RFC 5322) with the given attachments, as Postfix would pipe it. */
    fun email(
        to: String,
        vararg attachments: Triple<String, String, ByteArray>,
        from: String = "rechnung@lieferant.example",
    ): ByteArray {
        val message = MimeMessage(Session.getInstance(Properties()))
        message.setFrom(InternetAddress(from))
        message.setRecipient(jakarta.mail.Message.RecipientType.TO, InternetAddress(to))
        message.subject = "Ihre Rechnung"
        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText("Sehr geehrte Damen und Herren,\nanbei unsere Rechnung.", "UTF-8") })
        attachments.forEach { (name, type, bytes) ->
            multipart.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(bytes, type))
                    fileName = name
                    disposition = MimeBodyPart.ATTACHMENT
                    setHeader("Content-Transfer-Encoding", "base64")
                },
            )
        }
        message.setContent(multipart)
        message.saveChanges()
        return ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
    }
}
