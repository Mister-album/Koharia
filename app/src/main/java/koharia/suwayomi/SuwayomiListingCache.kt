package koharia.suwayomi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

internal class SuwayomiListingChangedException : IllegalStateException("Suwayomi listing changed")

/** Bounded, account-scoped raw pages; presentation filters remain outside the cache. */
internal class SuwayomiListingCache(
    private val api: SuwayomiService,
    private val json: Json,
    private val checkSession: () -> Unit,
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
) {
    private val mutex = Mutex()
    private val entries = linkedMapOf<Key, SuwayomiSourcePage>()
    private var hydrated = false
    private var revision = 0L

    private suspend fun hydrate() {
        checkSession()
        if (hydrated) return
        val stored = read()?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() }
        checkSession()
        stored.orEmpty().takeLast(MAX_PAGES).forEach { entries[it.key] = it.value }
        hydrated = true
    }

    suspend fun revision(): Long = mutex.withLock {
        hydrate()
        revision
    }

    suspend fun invalidate(): Unit = mutex.withLock {
        hydrate()
        entries.clear()
        revision++
        write("[]")
        checkSession()
    }

    suspend fun page(
        sourceId: Long,
        page: Int,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
        expectedRevision: Long,
    ): SuwayomiSourcePage = mutex.withLock {
        hydrate()
        // A refresh invalidates a complete generation, including pages an old pager appends later.
        if (expectedRevision != revision) throw SuwayomiListingChangedException()
        val key = Key(sourceId, page, query.orEmpty(), type, JsonArray(filters.map { it.toJson() }).toString())
        entries.remove(key)?.let {
            entries[key] = it
            return@withLock it
        }
        val result = api.discoverSourceManga(sourceId, page, query, type, filters)
        currentCoroutineContext().ensureActive()
        checkSession()
        commit(LinkedHashMap(entries).apply { put(key, result) })
        result
    }

    suspend fun refresh(
        sourceId: Long,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
    ) = mutex.withLock {
        hydrate()
        val key = Key(sourceId, 1, query.orEmpty(), type, JsonArray(filters.map { it.toJson() }).toString())
        val result = api.discoverSourceManga(sourceId, 1, query, type, filters)
        currentCoroutineContext().ensureActive()
        checkSession()
        val updated = LinkedHashMap(entries).apply {
            keys.removeAll { it.copy(page = 1) == key }
            put(key, result)
        }
        commit(updated)
        revision++
    }

    private suspend fun commit(updated: LinkedHashMap<Key, SuwayomiSourcePage>) {
        var payload = json.encodeToString(updated.map { Entry(it.key, it.value) })
        while (updated.size > MAX_PAGES || payload.length > MAX_CHARACTERS) {
            updated.remove(updated.keys.first())
            payload = json.encodeToString(updated.map { Entry(it.key, it.value) })
        }
        write(payload)
        checkSession()
        entries.clear()
        entries.putAll(updated)
    }

    @Serializable
    private data class Key(
        val sourceId: Long,
        val page: Int,
        val query: String,
        val type: SuwayomiSourceMangaType,
        val filters: String,
    )

    @Serializable
    private data class Entry(val key: Key, val value: SuwayomiSourcePage)

    companion object {
        private const val MAX_PAGES = 24
        private const val MAX_CHARACTERS = 1_000_000
    }
}
