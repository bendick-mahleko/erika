package de.erika.platform

import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The running application must connect as erika_app: no superuser, no BYPASSRLS,
 * no DDL (Software Design §7). Row-level security is only a safety net if this holds.
 */
class DatabaseRolesIT : WorkerIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `application connects as the restricted app role`() {
        assertEquals("erika_app", jdbc.sql("SELECT current_user").query(String::class.java).single())

        val (superuser, bypassRls) =
            jdbc
                .sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }
                .single()
        assertFalse(superuser, "app role must not be superuser")
        assertFalse(bypassRls, "app role must not bypass row-level security")
    }

    @Test
    fun `app role cannot change the schema`() {
        assertFailsWith<DataAccessException> { jdbc.sql("CREATE TABLE sneaky (id int)").update() }
        assertFailsWith<DataAccessException> { jdbc.sql("DROP TABLE job").update() }
    }

    @Test
    fun `app role can use tables created by migrations`() {
        jdbc.sql("SELECT count(*) FROM job").query(Long::class.java).single()
        jdbc.sql("SELECT count(*) FROM system_heartbeat").query(Long::class.java).single()
    }
}
