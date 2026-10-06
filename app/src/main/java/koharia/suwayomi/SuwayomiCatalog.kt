package koharia.suwayomi

import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionShelfCachePolicy
import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class SuwayomiCatalog(
    private val identity: SuwayomiIdentity,
    private val repository: SuwayomiRepository,
    private val api: SuwayomiService,
    private val json: Json,
    private val checkSession: () -> Unit,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(key: String) = locks.getOrPut(key, ::Mutex)

    @Volatile private var shelfSnapshot: SuwayomiShelf? = null

    @Volatile private var shelfInvalidated = false
    val memoryShelf get() = shelfSnapshot

    val sourceInventory = resource<List<SuwayomiSourceInfo>>("sources", fetch = { api.sources() })
    val extensionInventory = resource<List<SuwayomiExtension>>("extensions", fetch = { api.extensions() })
    val categoryInventory = resource<List<SuwayomiCategory>>(
        "categories",
        fetch = { api.categories() },
        fallback = { cachedShelf()?.categories },
    )
    private val filters = ConcurrentHashMap<Long, SuwayomiResource<List<SuwayomiSourceFilter>>>()
    private val preferences = ConcurrentHashMap<Long, SuwayomiResource<List<SuwayomiSourcePreference>>>()
    private val listings = SuwayomiListingCache(
        api,
        json,
        checkSession,
        read = { repository.cache(identity.connectionId, identity.account, "discovery", "pages")?.payload },
        write = { save("discovery", "pages", it) },
    )

    suspend fun sources(refresh: Boolean = false) = sourceInventory.load(refresh)
    suspend fun cachedSources() = sourceInventory.cached()
    suspend fun extensions(refresh: Boolean = false, fetchStores: Boolean = false): List<SuwayomiExtension> {
        if (!fetchStores) return extensionInventory.load(refresh)
        return extensionInventory.load(refresh = true) { api.extensions(true) }.also { sourceInventory.invalidate() }
    }
    suspend fun categories(refresh: Boolean = false) = categoryInventory.load(refresh)

    suspend fun source(id: Long): SuwayomiSourceInfo {
        return sources().firstOrNull { it.id == id } ?: api.source(id).also { checkSession() }
    }

    suspend fun sourceFilters(id: Long) = filters.getOrPut(id) {
        SuwayomiResource(checkSession, fetch = { api.sourceFilters(id) })
    }.load()

    fun sourcePreferencesState(id: Long) = preferenceResource(id).state
    private fun preferenceResource(id: Long) = preferences.getOrPut(id) {
        SuwayomiResource(checkSession, fetch = { api.sourcePreferences(id) })
    }
    suspend fun sourcePreferences(id: Long, refresh: Boolean = false) = preferenceResource(id).load(refresh)

    suspend fun updateSourcePreference(id: Long, position: Int, change: SuwayomiPreferenceChange) {
        preferenceResource(id).mutate(fresh = true) { api.updateSourcePreference(id, position, change) }
        filters[id]?.invalidate()
        listings.invalidate()
    }

    suspend fun setSourcePinned(id: Long, pinned: Boolean): SuwayomiSourceMeta {
        var result: SuwayomiSourceMeta? = null
        sourceInventory.mutate { previous ->
            val meta = api.setSourceMeta(id, SuwayomiSourceInfo.PINNED_META_KEY, pinned.toString())
            result = meta
            previous?.map { info ->
                if (info.id == id) info.copy(meta = info.meta.filterNot { it.key == meta.key } + meta) else info
            }
        }
        return checkNotNull(result)
    }

    suspend fun invalidateSourceConfiguration() {
        sourceInventory.invalidate()
        extensionInventory.invalidate()
        filters.values.forEach { it.invalidate() }
        preferences.values.forEach { it.invalidate() }
        listings.invalidate()
    }

    suspend fun listingRevision() = listings.revision()
    suspend fun invalidateListings() = listings.invalidate()
    suspend fun refreshSourceListing(
        sourceId: Long,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
    ) = listings.refresh(sourceId, query, type, filters)
    suspend fun sourceListing(
        sourceId: Long,
        page: Int,
        query: String?,
        type: SuwayomiSourceMangaType,
        filters: List<SuwayomiFilterChange>,
        revision: Long,
    ) = listings.page(sourceId, page, query, type, filters, revision)

    suspend fun hasPreparedManga(id: Int): Boolean =
        cached<SuwayomiManga>("manga", id.toString()) != null &&
            cached<List<SuwayomiChapter>>("chapters", id.toString()) != null

    private inline fun <reified T> resource(
        group: String,
        noinline fetch: suspend () -> T,
        noinline fallback: suspend () -> T? = { null },
    ) = SuwayomiResource(
        checkSession,
        fetch,
        read = {
            val entry = repository.cache(identity.connectionId, identity.account, group, "snapshot")
            val value = entry?.let { runCatching { json.decodeFromString<T>(it.payload) }.getOrNull() }
            if (value != null) {
                SuwayomiSnapshot(value, entry.generation > 0)
            } else {
                fallback()?.let { SuwayomiSnapshot(it, true) }
            }
        },
        write = { value, valid ->
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.putCache(
                identity.connectionId,
                identity.account,
                group,
                SuwayomiCacheEntry("snapshot", json.encodeToString(value), if (valid) 1 else 0),
            )
        },
    )

    suspend fun cachedShelf(): SuwayomiShelf? {
        checkSession()
        if (!shelfInvalidated) shelfSnapshot?.let { return it }
        val entry = repository.cache(identity.connectionId, identity.account, SHELF_GROUP, SHELF_KEY)
            ?: return null
        shelfInvalidated = entry.generation <= SHELF_TOMBSTONE_GENERATION
        if (entry.payload.isEmpty()) return null
        return runCatching { json.decodeFromString<SuwayomiShelf>(entry.payload) }
            .onFailure { logcat(LogPriority.WARN) { "Discarding an unusable Suwayomi shelf snapshot" } }
            .getOrNull().also {
                checkSession()
                shelfSnapshot = it
            }.takeUnless { shelfInvalidated }
    }

    /**
     * Marks the persisted shelf snapshot stale after a write that changes library membership, so the
     * next read fetches the server's new state instead of the pre-write snapshot.
     */
    suspend fun invalidateShelf(): Unit = lock("shelf").withLock {
        checkSession()
        val previous = repository.cache(identity.connectionId, identity.account, SHELF_GROUP, SHELF_KEY)
        checkSession()
        shelfInvalidated = true
        repository.putCache(
            identity.connectionId,
            identity.account,
            SHELF_GROUP,
            SuwayomiCacheEntry(SHELF_KEY, previous?.payload.orEmpty(), SHELF_TOMBSTONE_GENERATION),
        )
    }

    suspend fun shelf(refresh: Boolean = false): SuwayomiShelf = lock("shelf").withLock {
        val cached = cachedShelf()
        if (!ConnectionShelfCachePolicy.shouldRefresh(cached != null, refresh)) return@withLock checkNotNull(cached)
        val categories = api.categories()
        val mangas = linkedMapOf<Int, SuwayomiManga>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        do {
            val page = api.libraryPage(cursor)
            page.nodes.forEach { entry ->
                if (entry.id <= 0 || !entry.inLibrary) throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
                mangas[entry.id] = entry
            }
            cursor = if (page.pageInfo.hasNextPage) {
                page.pageInfo.endCursor?.takeIf { cursors.add(it) }
                    ?: throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            checkSession()
        } while (cursor != null)
        val result = SuwayomiShelf(mangas.values.toList(), categories.sortedBy { it.order })
        save(SHELF_GROUP, SHELF_KEY, json.encodeToString(result))
        checkSession()
        shelfSnapshot = result
        shelfInvalidated = false
        categoryInventory.accept(result.categories)
        result
    }

    suspend fun manga(id: Int, refresh: Boolean = false): SuwayomiManga = lock("manga/$id").withLock {
        if (!refresh) {
            cached<SuwayomiManga>("manga", id.toString())?.let { return@withLock it }
            cachedShelf()?.mangas?.firstOrNull { it.id == id }?.let { return@withLock it }
        }
        api.manga(id).also { save("manga", id.toString(), json.encodeToString(it)) }
    }

    suspend fun chapters(id: Int, refresh: Boolean = false): List<SuwayomiChapter> = lock("chapters/$id").withLock {
        if (!refresh) cached<List<SuwayomiChapter>>("chapters", id.toString())?.let { return@withLock it }
        api.chapters(id).also { result ->
            if (result.any { it.id <= 0 || it.mangaId != id } || result.map { it.id }.distinct().size != result.size) {
                throw SuwayomiException(SuwayomiException.Reason.PROTOCOL)
            }
            save("chapters", id.toString(), json.encodeToString(result))
        }
    }

    suspend fun pages(id: Int, refresh: Boolean = false): SuwayomiPages = loadPages(id, refresh, false)

    suspend fun pagesForReading(id: Int, online: Boolean, forceNetwork: Boolean): SuwayomiPages =
        loadPages(id, refresh = forceNetwork || online, allowCachedOnTransportFailure = !forceNetwork)

    private suspend fun loadPages(
        id: Int,
        refresh: Boolean,
        allowCachedOnTransportFailure: Boolean,
    ): SuwayomiPages = lock("pages/$id").withLock {
        currentCoroutineContext().ensureActive()
        checkSession()
        if (!refresh) cachedPages(id)?.let { return@withLock it }
        val result = try {
            api.pages(id)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            checkSession()
            if (!allowCachedOnTransportFailure || generateSequence<Throwable>(error) { it.cause }.any {
                    it is ConnectionAddressRouter.NonRoutingFailure || it is CancellationException
                }
            ) {
                throw error
            }
            cachedPages(id)?.let { return@withLock it }
            throw error
        }
        save("pages", id.toString(), json.encodeToString(result))
        result
    }

    private suspend fun cachedPages(id: Int): SuwayomiPages? {
        val result = cached<SuwayomiPages>("pages", id.toString())
        currentCoroutineContext().ensureActive()
        checkSession()
        return result
    }

    private suspend inline fun <reified T> cached(group: String, key: String): T? {
        checkSession()
        return repository.cache(identity.connectionId, identity.account, group, key)
            ?.let { runCatching { json.decodeFromString<T>(it.payload) }.getOrNull() }
            .also { checkSession() }
    }

    private suspend fun save(group: String, key: String, payload: String) {
        currentCoroutineContext().ensureActive()
        checkSession()
        repository.putCache(
            identity.connectionId,
            identity.account,
            group,
            SuwayomiCacheEntry(key, payload, System.currentTimeMillis()),
        )
    }

    companion object {
        private const val SHELF_GROUP = "shelf"
        private const val SHELF_KEY = "snapshot"

        /** Generations at or below this mark a snapshot as stale; real snapshots use wall-clock time. */
        private const val SHELF_TOMBSTONE_GENERATION = 0L
    }
}
