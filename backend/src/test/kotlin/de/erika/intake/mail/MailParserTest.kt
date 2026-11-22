package de.erika.intake.mail

import de.erika.intake.ClamAvScanner
import de.erika.intake.ScanResult
import de.erika.intake.VirusScanUnavailableException
import de.erika.support.Samples
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailParserTest {
    @Test
    fun `XML, PDF and ZIP attachments are found, the text body is not`() {
        val xml = "<Invoice/>".toByteArray()
        val pdf = "%PDF-1.4 x".toByteArray()
        val raw =
            Samples.email(
                "abc@in.erika.local",
                Triple("rechnung.xml", "application/xml", xml),
                Triple("Rechnung März.pdf", "application/pdf", pdf),
                Triple("logo.png", "image/png", byteArrayOf(1, 2, 3)),
                Triple("archiv.zip", "application/zip", Samples.zip("a.xml" to xml)),
            )
        val files = MailParser.attachments(raw)
        assertEquals(listOf("rechnung.xml", "Rechnung März.pdf", "archiv.zip"), files.map { it.name })
        assertContentEquals(xml, files[0].bytes)
        assertContentEquals(pdf, files[1].bytes)
    }

    @Test
    fun `attachments of a forwarded email are found`() {
        val inner = Samples.email("x@in.erika.local", Triple("weitergeleitet.xml", "text/xml", "<a/>".toByteArray()))
        val outer = Samples.email("abc@in.erika.local", Triple("original.eml", "message/rfc822", inner))
        assertEquals(listOf("weitergeleitet.xml"), MailParser.attachments(outer).map { it.name })
    }

    @Test
    fun `an email without attachments yields nothing`() {
        assertTrue(MailParser.attachments(Samples.email("abc@in.erika.local")).isEmpty())
    }

    @Test
    fun `ClamAV replies are understood, anything else is an error, never "clean"`() {
        assertEquals(ScanResult.Clean, ClamAvScanner.parse("stream: OK"))
        assertEquals(ScanResult.Infected("Win.Test.EICAR_HDB-1"), ClamAvScanner.parse("stream: Win.Test.EICAR_HDB-1 FOUND"))
        assertFailsWith<VirusScanUnavailableException> { ClamAvScanner.parse("INSTREAM size limit exceeded. ERROR") }
        assertFailsWith<VirusScanUnavailableException> { ClamAvScanner.parse("") }
    }
}
