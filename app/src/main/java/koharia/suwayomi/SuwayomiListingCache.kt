package koharia.suwayomi

import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.ByteString.Companion.encodeUtf8

internal class SuwayomiListingChangedException : IllegalStateException("Suwayomi listing changed")

/** Each account-scoped page survives eviction from Paging's bounded presentation window. */
internal class SuwayomiListingCache(
    private val api: SuwayomiService,
    private val json: Json,
    private val checkSession: () -> Unit,
    private val identity: SuwayomiIdentity,
    private val repository: SuwayomiRepository,
) {
    private val mutex = Mutex()
    private var hydrated = false
    private var revision = 0L

    private suspend fun hydrate() {
        checkSession()
        if (hydrated) return
        val legacy = repository.cache(identity.connectionId, identity.account, "discovery", "pages")
        if (legacy != null) {
            val stored = try {
                json.decodeFromString<List<Entry>>(legacy.payload)
            } catch (_: kotlinx.serialization.SerializationException) {
                // Retain unreadable legacy data; a bad old aggregate must not hide valid new pages.
                hydrated = true
                return
            }
            val groups = stored.groupBy { group(it.key) }.mapValues { (_, entries) ->
                entries.map { entry(it.key.page, it.value) }
            }
            currentCoroutineContext().ensureActive()
            checkSession()
            // Import and retirement of the old aggregate are atomic, so interruption cannot lose cached pages.
            repository.migrateDiscoveryCache(identity.connectionId, identity.account, groups)
        }
        checkSession()
        hydrated = true
    }

    suspend fun revision(): Long = mutex.withLock {
        hydrate()
        revision
    }

    suspend fun invalidate(): Unit = mutex.withLock {
        checkSession()
        withContext(NonCancellable) {
            repository.invalidateDiscoveryCache(identity.connectionId, identity.account)
            revision++
            hydrated = true
        }
        currentCoroutineContext().ensureActive()
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
        require(page > 0)
        hydrate()
        if (expectedRevision != revision) throw SuwayomiListingChangedException()
        val group = group(key(sourceId, page, query, type, filters))
        val stored = repository.cache(identity.connectionId, identity.account, group, page.toString())
        checkSession()
        if (stored != null) return@withLock json.decodeFromString(stored.payload)
        val result = api.discoverSourceManga(sourceId, page, query, type, filters)
        currentCoroutineContext().ensureActive()
        checkSession()
        repository.putCache(identity.connectionId, identity.account, group, entry(page, result))
        checkSession()
        result
    }

    suspend fun refresh(
        sourceId: Long,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
    ) = mutex.withLock {
        hydrate()
        val result = api.discoverSourceManga(sourceId, 1, query, type, filters)
        currentCoroutineContext().ensureActive()
        checkSession()
        withContext(NonCancellable) {
            repository.replaceCacheGroup(
                identity.connectionId,
                identity.account,
                group(key(sourceId, 1, query, type, filters)),
                listOf(entry(1, result)),
            )
            revision++
        }
        currentCoroutineContext().ensureActive()
        checkSession()
    }

    private fun key(
        sourceId: Long,
        page: Int,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
    ) = Key(sourceId, page, query.orEmpty(), type, JsonArray(filters.map { it.toJson() }).toString())

    private fun group(key: Key) = "discovery/" + json.encodeToString(key.copy(page = 1)).encodeUtf8().sha256().hex()
    private fun entry(page: Int, value: SuwayomiSourcePage) =
        SuwayomiCacheEntry(page.toString(), json.encodeToString(value), 1)

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
}
