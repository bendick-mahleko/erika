package de.erika.workorders

import de.erika.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** With `erika.features.mara` off (the production default), the MARA endpoints don't exist. */
@TestPropertySource(properties = ["erika.features.mara=false"])
class MaraFeatureOffIT : ApiIntegrationTest() {
    @Test
    fun `MARA endpoints answer 404 and the session says the feature is off`() {
        val c = client()
        accounts.enrolled("ohne-mara", c)
        listOf("/api/v1/work-orders", "/api/v1/sites", "/api/v1/staff", "/api/v1/staff-levels", "/api/v1/checklist-templates").forEach {
            val r = c.get(it)
            assertEquals(404, r.status, "$it: $r")
            assertEquals("FEATURE_DISABLED", r.code)
        }
        assertFalse(
            c
                .get("/api/v1/auth/session")
                .tree
                .path("features")
                .path("mara")
                .asBoolean(true),
        )
    }
}
