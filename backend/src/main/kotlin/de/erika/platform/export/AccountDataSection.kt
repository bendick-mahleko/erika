package de.erika.platform.export

import java.util.UUID

/**
 * One part of the full account export (EX-04, NF-09). Each feature module contributes its own
 * data as a Spring bean implementing this interface, so the `export` module never reads another
 * module's tables. The originals themselves come from `archive.Archive`.
 */
interface AccountDataSection {
    /** File name in the ZIP's `daten/` folder, e.g. `dokumente.json`. Unique across sections. */
    val fileName: String

    /**
     * The workspace's data as maps, lists and plain values (written as JSON). Called by the
     * worker in a transaction scoped to [workspaceId], so row-level security applies.
     */
    fun collect(workspaceId: UUID): Any
}

/**
 * Runs [sql] (a SELECT under the caller's row-level security) and returns its rows as a JSON
 * array, with the column names as keys. For [AccountDataSection]s that export tables as they are.
 */
fun org.springframework.jdbc.core.simple.JdbcClient.jsonRows(
    json: tools.jackson.databind.json.JsonMapper,
    sql: String,
): tools.jackson.databind.JsonNode =
    json.readTree(
        sql("SELECT coalesce(json_agg(t), '[]'::json)::text FROM ($sql) t")
            .query(String::class.java)
            .single(),
    )
