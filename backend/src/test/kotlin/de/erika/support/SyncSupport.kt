package de.erika.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A record as the phone holds it. */
data class LocalRecord(
    val entity: String,
    val id: String,
    val version: Long,
    val fieldVersions: Map<String, Long>,
    val data: Map<String, Any?>,
)

/** A pending change in the phone's outbox. */
data class PendingMutation(
    val mutationId: String,
    val entity: String,
    val entityId: String,
    val op: String,
    val values: Map<String, Any?>,
    val baseVersions: Map<String, Long>,
) {
    fun toJson() =
        mapOf(
            "mutationId" to mutationId,
            "entity" to entity,
            "entityId" to entityId,
            "op" to op,
            "values" to values,
            "baseVersions" to baseVersions,
        )
}

/**
 * The phone's side of the sync protocol, as small as possible (ADR 0038): a record store, a
 * cursor and an outbox. Mirrors what the Flutter app does, for the back end's tests.
 */
class TestSyncDevice(
    val phone: MobileClient,
) {
    private val json = JsonMapper.builder().build()
    val records = linkedMapOf<Pair<String, String>, LocalRecord>()
    val outbox = mutableListOf<PendingMutation>()

    /**
     * Records removed here, with a version: a late, older answer (a replayed pull) must not bring
     * them back. A removal's version is its page's cursor; a confirmed delete's the record's last.
     */
    val tombstones = mutableMapOf<Pair<String, String>, Long>()
    var cursor = ""
    val results = mutableListOf<JsonNode>()

    fun record(
        entity: String,
        id: Any,
    ): LocalRecord? = records[entity to id.toString()]

    fun all(entity: String) = records.values.filter { it.entity == entity }

    /** Pulls until there is nothing more; returns the removals seen. */
    fun pullAll(): List<Pair<String, String>> {
        val removals = mutableListOf<Pair<String, String>>()
        do {
            val r = phone.get("/api/v1/mobile/sync/pull?protocolVersion=1&cursor=$cursor")
            check(r.status == 200) { "pull: $r" }
            r.tree
                .path("records")
                .values()
                .forEach { store(it) }
            val pageCursor =
                r.tree
                    .path("nextCursor")
                    .asString()
                    .toLongOrNull() ?: 0
            r.tree.path("removals").values().forEach {
                val key = it.path("entity").asString() to it.path("id").asString()
                // A record with a pending change stays until its push result arrives (ADR 0038).
                if (outbox.none { m -> m.entity == key.first && m.entityId == key.second }) {
                    records.remove(key)
                    tombstones.merge(key, pageCursor, ::maxOf)
                }
                removals += key
            }
            cursor = r.tree.path("nextCursor").asString()
        } while (r.tree.path("hasMore").asBoolean())
        return removals
    }

    /** Queues a change of [values] against the versions this phone saw. */
    fun change(
        entity: String,
        id: Any,
        values: Map<String, Any?>,
    ): PendingMutation {
        val local = checkNotNull(record(entity, id)) { "no local $entity $id" }
        val m =
            PendingMutation(
                UUID.randomUUID().toString(),
                entity,
                id.toString(),
                "UPDATE",
                values,
                values.keys.associateWith { local.fieldVersions[it] ?: 0L },
            )
        outbox += m
        return m
    }

    fun create(
        entity: String,
        id: Any,
        values: Map<String, Any?>,
    ): PendingMutation =
        PendingMutation(UUID.randomUUID().toString(), entity, id.toString(), "CREATE", values, emptyMap()).also {
            outbox +=
                it
        }

    /** Queues a delete with the versions of every field this phone saw (ADR 0038). */
    fun delete(
        entity: String,
        id: Any,
    ): PendingMutation {
        val local = checkNotNull(record(entity, id)) { "no local $entity $id" }
        val m = PendingMutation(UUID.randomUUID().toString(), entity, id.toString(), "DELETE", emptyMap(), local.fieldVersions)
        outbox += m
        return m
    }

    /** Sends the outbox; removes the entries that got a result. Returns the raw response. */
    fun push(): Response {
        val r = phone.post("/api/v1/mobile/sync/push", mapOf("protocolVersion" to 1, "mutations" to outbox.map { it.toJson() }))
        check(r.status == 200) { "push: $r" }
        r.tree.path("results").values().forEach { result ->
            results.add(result)
            val sent = outbox.firstOrNull { it.mutationId == result.path("mutationId").asString() }
            outbox.removeIf { it.mutationId == result.path("mutationId").asString() }
            if (sent?.op == "DELETE" && result.path("status").asString() == "APPLIED") {
                val key = sent.entity to sent.entityId
                records.remove(key)?.let { tombstones.merge(key, it.version, ::maxOf) }
            }
            if (!result.path("record").isMissingNode && !result.path("record").isNull) store(result.path("record"))
        }
        return r
    }

    private fun store(node: JsonNode) {
        @Suppress("UNCHECKED_CAST")
        val data = json.convertValue(node.path("data"), Map::class.java) as Map<String, Any?>
        val versions = node.path("fieldVersions").properties().associate { (k, v) -> k to v.asLong() }
        val rec = LocalRecord(node.path("entity").asString(), node.path("id").asString(), node.path("version").asLong(), versions, data)
        val key = rec.entity to rec.id
        if ((tombstones[key] ?: -1) >= rec.version) return
        tombstones.remove(key)
        // Pull results can arrive after newer push results: keep the newest.
        val existing = records[key]
        if (existing == null || existing.version <= rec.version) records[key] = rec
    }
}
