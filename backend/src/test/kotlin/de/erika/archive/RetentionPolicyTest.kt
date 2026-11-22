package de.erika.archive

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import kotlin.test.assertEquals

/** BR-02: 8 years from the end of the year of issue (or receipt); 10 years for invoices dated before 2025. */
class RetentionPolicyTest {
    @ParameterizedTest(name = "issued {0}, received {1} -> kept until {2}")
    @CsvSource(
        // 8-year rule
        "2025-01-01, 2025-01-03, 2033-12-31",
        "2026-11-22, 2026-11-22, 2034-12-31",
        "2026-12-31, 2027-01-02, 2034-12-31", // issued on the last day of the year: issue year counts
        "2027-01-01, 2027-01-01, 2035-12-31",
        // 10-year rule for invoices dated before 1 Jan 2025
        "2024-12-31, 2025-01-02, 2034-12-31", // received in 2025, but dated 2024
        "2024-01-01, 2024-01-05, 2034-12-31",
        "2016-06-30, 2016-07-01, 2026-12-31",
        // issue date unknown (plain PDF, unreadable file): the receipt date decides
        ", 2025-01-01, 2033-12-31",
        ", 2024-12-31, 2034-12-31",
        ", 2026-11-22, 2034-12-31",
    )
    fun retentionUntil(
        issued: LocalDate?,
        received: LocalDate,
        expected: LocalDate,
    ) {
        assertEquals(expected, RetentionPolicy.retentionUntil(issued, received))
    }
}
