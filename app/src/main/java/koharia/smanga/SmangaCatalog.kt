package koharia.smanga

import koharia.connection.ConnectionShelfCachePolicy
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.ConcurrentHashMap

/** An instance belongs to one immutable authenticated connection session. */
class SmangaCatalog(
    private val connectionId: Long,
    private val accountKey: String,
    private val repository: SmangaRepository,
    private val api: SmangaApi,
    private val json: Json,
    private val checkSession: () -> Unit,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(group: String) = locks.getOrPut(group) { Mutex() }

    private suspend inline fun <reified T> cached(
        group: String,
        key: String = "data",
        refresh: Boolean = false,
        fetch: () -> T,
    ): T =
        lock(group).withLock {
            checkSession()
            val old = repository.cache(connectionId, accountKey, group, key)
            currentCoroutineContext().ensureActive()
            checkSession()
            if (!ConnectionShelfCachePolicy.shouldRefresh(old != null, refresh)) {
                return@withLock json.decodeFromString<T>(old!!.payload)
            }
            val value = fetch()
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.putCache(
                connectionId,
                accountKey,
                group,
                SmangaCacheEntry(key, json.encodeToString(value), System.currentTimeMillis()),
            )
            value
        }

    suspend fun media(refresh: Boolean = false): List<SmangaMedia> = cached("media", refresh = refresh) { api.media() }

    suspend fun cachedMedia(): List<SmangaMedia>? =
        cachedEntry("media", "data")?.let { json.decodeFromString<List<SmangaMedia>>(it.payload) }

    /** Publish added libraries only after their shelf is ready; revoked access must take effect even on failure. */
    suspend fun refreshShelf(selectedMedia: Long, query: String, order: String, downloadedOnly: Boolean = false) {
        lock("media").withLock {
            val old = cachedMedia().orEmpty()
            val media = api.media()
            val ids = media.map { it.id }.toSet()
            val entry = SmangaCacheEntry("data", json.encodeToString(media), System.currentTimeMillis())
            val revoked = old.any { it.id !in ids }
            if (revoked) putCacheEntry("media", entry)
            val selected = selectedMedia.takeIf { it in ids } ?: 0
            if (!downloadedOnly) {
                refresh(media.filter { selected == 0L || it.id == selected }.map { it.id }, query, order)
            }
            if (!revoked) putCacheEntry("media", entry)
        }
    }

    suspend fun manga(
        id: Long,
        refresh: Boolean = false,
    ): SmangaManga = cached("manga/$id", refresh = refresh) { api.manga(id) }
    suspend fun chapters(mangaId: Long, refresh: Boolean = false): List<SmangaChapter> =
        cached("chapters/$mangaId", refresh = refresh) { api.chapters(mangaId) }
    suspend fun chapter(id: Long): SmangaChapter = cached("chapter/$id") { api.chapter(id) }
    suspend fun manifest(id: Long, refresh: Boolean = false): SmangaPageManifest =
        cached("manifest/$id", refresh = refresh) { api.preparePages(id) }

    private fun shelfKey(mediaId: Long, query: String, order: String) =
        "shelf/$mediaId/" + json.encodeToString(listOf(query, order)).encodeUtf8().sha256().hex()

    suspend fun page(mediaId: Long, query: String, order: String, page: Int): SmangaMangaPage {
        val group = shelfKey(mediaId, query, order)
        return lock(group).withLock {
            checkSession()
            repository.cache(connectionId, accountKey, group, page.toString())?.let {
                currentCoroutineContext().ensureActive()
                checkSession()
                return@withLock json.decodeFromString<SmangaMangaPage>(it.payload)
            }
            val result = api.mangas(mediaId, page, query = query, order = order)
            currentCoroutineContext().ensureActive()
            checkSession()
            val generation =
                repository.cache(connectionId, accountKey, group, "1")?.generation ?: System.currentTimeMillis()
            repository.putCache(
                connectionId,
                accountKey,
                group,
                SmangaCacheEntry(page.toString(), json.encodeToString(result), System.currentTimeMillis(), generation),
            )
            result
        }
    }

    /** The backend's unscoped manga endpoint does not enforce library permissions. */
    suspend fun page(mediaIds: List<Long>, query: String, order: String, page: Int): SmangaMangaPage {
        require(page > 0)
        val ids = mediaIds.distinct().sorted()
        require(ids.all { it > 0 })
        checkSession()
        if (ids.isEmpty()) return SmangaMangaPage(emptyList(), page, 100, 0)
        if (ids.size == 1) return page(ids.single(), query, order, page)
        val group = mergedShelfKey(ids, query, order)
        return lock(group).withLock {
            readMergedPage(group, page)?.let { return@withLock it.page }
            val generation = cachedEntry(group, "generation")?.generation ?: System.currentTimeMillis().also {
                putCacheEntry(group, SmangaCacheEntry("generation", "{}", it, it))
            }
            var previous = if (page > 1) readMergedPage(group, page - 1) else null
            val start = if (previous != null) page else 1
            for (index in start..page) {
                val cached = readMergedPage(group, index)
                if (cached != null) {
                    previous = cached
                    continue
                }
                val staged = ConcurrentHashMap<String, SmangaCacheEntry>()
                val merged = mergeSmangaShelfPage(ids, order, index, previous) { id, next ->
                    val key = "media/$id/$next"
                    cachedEntry(group, key)?.let { json.decodeFromString<SmangaMangaPage>(it.payload) }
                        ?: api.mangas(id, next, query = query, order = order).also {
                            staged[key] = SmangaCacheEntry(key, json.encodeToString(it), generation, generation)
                        }
                }
                staged.values.forEach { putCacheEntry(group, it) }
                putCacheEntry(
                    group,
                    SmangaCacheEntry("page/$index", json.encodeToString(merged), generation, generation),
                )
                previous = merged
            }
            checkSession()
            checkNotNull(previous).page
        }
    }

    suspend fun refresh(mediaIds: List<Long>, query: String, order: String) {
        val ids = mediaIds.distinct().sorted()
        require(ids.all { it > 0 })
        checkSession()
        if (ids.isEmpty()) return
        if (ids.size == 1) return refresh(ids.single(), query, order)
        val group = mergedShelfKey(ids, query, order)
        lock(group).withLock {
            val loadedPages = repository.cacheGroup(connectionId, accountKey, group)
                .filter { it.key.startsWith("page/") }
                .mapNotNull { it.key.substringAfter('/').toIntOrNull() }
                .maxOrNull() ?: 1
            val generation = System.currentTimeMillis()
            val staged = ConcurrentHashMap<String, SmangaCacheEntry>()
            staged["generation"] = SmangaCacheEntry("generation", "{}", generation, generation)
            var previous: SmangaMergedShelfPage? = null
            for (index in 1..loadedPages) {
                val merged = mergeSmangaShelfPage(ids, order, index, previous) { id, next ->
                    val key = "media/$id/$next"
                    staged[key]?.let { json.decodeFromString<SmangaMangaPage>(it.payload) }
                        ?: api.mangas(id, next, query = query, order = order).also {
                            staged[key] = SmangaCacheEntry(key, json.encodeToString(it), generation, generation)
                        }
                }
                val key = "page/$index"
                staged[key] = SmangaCacheEntry(key, json.encodeToString(merged), generation, generation)
                previous = merged
                if (!merged.page.hasNext) break
            }
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.replaceCacheGroup(connectionId, accountKey, group, staged.values.toList())
        }
    }

    private fun mergedShelfKey(mediaIds: List<Long>, query: String, order: String) =
        "shelf/all/" + json.encodeToString(listOf(mediaIds.joinToString(","), query, order)).encodeUtf8().sha256().hex()

    private suspend fun cachedEntry(group: String, key: String): SmangaCacheEntry? {
        checkSession()
        val entry = repository.cache(connectionId, accountKey, group, key)
        currentCoroutineContext().ensureActive()
        checkSession()
        return entry
    }

    private suspend fun readMergedPage(group: String, page: Int): SmangaMergedShelfPage? =
        cachedEntry(group, "page/$page")?.let { json.decodeFromString<SmangaMergedShelfPage>(it.payload) }

    private suspend fun putCacheEntry(group: String, entry: SmangaCacheEntry) {
        currentCoroutineContext().ensureActive()
        checkSession()
        repository.putCache(connectionId, accountKey, group, entry)
    }

    /** Stage every previously loaded page before replacing a shelf; failures retain all old pages. */
    suspend fun refresh(mediaId: Long, query: String, order: String) {
        val group = shelfKey(mediaId, query, order)
        lock(group).withLock {
            checkSession()
            val count = repository.cacheGroup(connectionId, accountKey, group).maxOfOrNull { it.key.toInt() } ?: 1
            val generation = System.currentTimeMillis()
            val staged = mutableListOf<SmangaCacheEntry>()
            for (page in 1..count) {
                val result = api.mangas(mediaId, page, query = query, order = order)
                staged += SmangaCacheEntry(page.toString(), json.encodeToString(result), generation, generation)
                if (!result.hasNext) break
            }
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.replaceCacheGroup(connectionId, accountKey, group, staged)
        }
    }
}
