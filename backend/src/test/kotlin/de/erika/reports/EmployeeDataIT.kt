package de.erika.reports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MARA Stage 10 (M-NF-09): employees' time records are seen by the employee, owners and the
 * office, not by tax advisors; technicians only see times of jobs they work on (sync scope). No
 * rankings or scores exist anywhere: the API has no per-person totals.
 */
class EmployeeDataIT : SignedReportTest() {
    @Test
    fun `tax advisors see the report but not the employees' raw times, and can't export them`() {
        val f = firm("employee-data")
        record(f)
        val ownerView = f.owner.get("/api/v1/work-orders/${f.order}/report").tree
        assertEquals(1, ownerView.path("timeEntries").size(), "owners see the times")

        val (_, advisor) = accounts.invited(f.owner, "ADVISOR", "employee-data-advisor")
        val advisorView = advisor.get("/api/v1/work-orders/${f.order}/report")
        assertEquals(200, advisorView.status)
        assertTrue(advisorView.tree.path("timeEntries").isEmpty, "advisors get no time records")
        assertEquals(1, advisorView.tree.path("materials").size(), "the rest of the report is visible")
        assertEquals(403, advisor.get("/api/v1/reports/time-entries.csv?from=2020-01-01&to=2020-12-31").status)

        val (_, member) = accounts.invited(f.owner, "MEMBER", "employee-data-office")
        assertEquals(
            1,
            member
                .get("/api/v1/work-orders/${f.order}/report")
                .tree
                .path("timeEntries")
                .size(),
            "the office sees them",
        )
    }
}
