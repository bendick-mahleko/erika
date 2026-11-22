package de.erika.intake

import de.erika.support.Samples
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ZIP limits of Software Design §5 (NF-04): depth 2, 50 files, zip-bomb ratio. */
class ZipUnpackerTest {
    private val unpacker = ZipUnpacker(ZipLimits(), maxFileBytes = 20L * 1024 * 1024)

    @Test
    fun `files are returned with their path inside the ZIP, folders and hidden files are skipped`() {
        val zip =
            Samples.zip(
                "a/re-1.xml" to "<a/>".toByteArray(),
                "__MACOSX/a/._re-1.xml" to byteArrayOf(1),
                "a/.DS_Store" to byteArrayOf(1),
            )
        val result = unpacker.unpack("x.zip", zip)
        assertEquals(listOf("x.zip/a/re-1.xml"), result.files.map { it.name })
        assertEquals(
            "<a/>",
            result.files
                .single()
                .bytes
                .decodeToString(),
        )
    }

    @Test
    fun `a ZIP in a ZIP is unpacked, one level deeper is rejected`() {
        val inner = Samples.zip("re.xml" to "<a/>".toByteArray())
        assertEquals(
            listOf("outer.zip/inner.zip/re.xml"),
            unpacker.unpack("outer.zip", Samples.zip("inner.zip" to inner)).files.map { it.name },
        )

        val tooDeep = Samples.zip("l2.zip" to Samples.zip("l3.zip" to inner))
        assertEquals(IntakeReason.ZIP_TOO_DEEP, assertFailsWith<ZipRejectedException> { unpacker.unpack("l1.zip", tooDeep) }.reason)
    }

    @Test
    fun `more than 50 files are rejected`() {
        val fifty = (1..50).map { "re-$it.xml" to "<a>$it</a>".toByteArray() }.toTypedArray()
        assertEquals(50, unpacker.unpack("ok.zip", Samples.zip(*fifty)).files.size)
        val fiftyOne = (1..51).map { "re-$it.xml" to "<a>$it</a>".toByteArray() }.toTypedArray()
        assertEquals(
            IntakeReason.ZIP_TOO_MANY_FILES,
            assertFailsWith<ZipRejectedException> {
                unpacker.unpack("x.zip", Samples.zip(*fiftyOne))
            }.reason,
        )
    }

    @Test
    fun `a zip bomb is rejected without inflating it completely`() {
        assertEquals(IntakeReason.ZIP_BOMB, assertFailsWith<ZipRejectedException> { unpacker.unpack("b.zip", Samples.zipBomb()) }.reason)
        // Small but extreme ratio (2 MB of zeros): also a bomb.
        val small = Samples.zip("x.xml" to ByteArray(2 * 1024 * 1024))
        assertEquals(IntakeReason.ZIP_BOMB, assertFailsWith<ZipRejectedException> { unpacker.unpack("s.zip", small) }.reason)
    }

    @Test
    fun `a file over the size limit that is not a bomb is refused alone`() {
        val strict = ZipUnpacker(ZipLimits(), maxFileBytes = 1000)
        val random = ByteArray(5000).also { java.util.Random(1).nextBytes(it) }
        val result = strict.unpack("z.zip", Samples.zip("gross.pdf" to random, "klein.xml" to "<a/>".toByteArray()))
        assertEquals(listOf(RefusedEntry("z.zip/gross.pdf", IntakeReason.FILE_TOO_LARGE)), result.refused)
        assertEquals(listOf("z.zip/klein.xml"), result.files.map { it.name })
    }

    @Test
    fun `damaged ZIPs are rejected`() {
        val zip = Samples.zip("re.xml" to ByteArray(10_000) { (it % 251).toByte() })
        val damaged = zip.copyOf(zip.size / 2).also { it.fill(0x55, 40, 60) }
        assertEquals(IntakeReason.ZIP_DAMAGED, assertFailsWith<ZipRejectedException> { unpacker.unpack("d.zip", damaged) }.reason)
    }

    @Test
    fun `paths that try to leave the archive only change the displayed name`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("../../etc/passwd.xml"))
            it.write("<a/>".toByteArray())
            it.closeEntry()
        }
        val file = unpacker.unpack("p.zip", out.toByteArray()).files.single()
        assertEquals("passwd.xml", IntakePipeline.displayName(file.name))
    }

    @Test
    fun `files are recognised by content, not by name`() {
        assertEquals("application/pdf", IntakePipeline.mediaTypeOf("%PDF-1.7 ...".toByteArray()))
        assertEquals("application/xml", IntakePipeline.mediaTypeOf("  <Invoice/>".toByteArray()))
        assertEquals(null, IntakePipeline.mediaTypeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertTrue(ZipUnpacker.isZip(Samples.zip()))
    }
}
