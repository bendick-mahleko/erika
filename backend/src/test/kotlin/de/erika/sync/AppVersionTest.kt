package de.erika.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    private val props = MobileProperties(minAppVersion = "1.2.0", recommendedAppVersion = "1.4.0")

    @Test
    fun `parses versions and ignores pre-release and build parts`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
        assertEquals(AppVersion(2, 0, 1), AppVersion.parse("2.0.1+57"))
        assertEquals(AppVersion(2, 0, 1), AppVersion.parse(" 2.0.1-beta.3 "))
        assertNull(AppVersion.parse("one.two"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse(null))
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9), "compares numerically, not as text")
    }

    @Test
    fun `advises an update by version`() {
        assertEquals(UpdateAdvice.REQUIRED, updateAdvice("1.1.9", props))
        assertEquals(UpdateAdvice.RECOMMENDED, updateAdvice("1.2.0", props))
        assertEquals(UpdateAdvice.RECOMMENDED, updateAdvice("1.3.99", props))
        assertEquals(UpdateAdvice.NONE, updateAdvice("1.4.0", props))
        assertEquals(UpdateAdvice.NONE, updateAdvice("10.0.0", props))
    }

    @Test
    fun `no advice without a readable version`() {
        assertEquals(UpdateAdvice.NONE, updateAdvice(null, props))
        assertEquals(UpdateAdvice.NONE, updateAdvice("unknown", props))
    }
}
