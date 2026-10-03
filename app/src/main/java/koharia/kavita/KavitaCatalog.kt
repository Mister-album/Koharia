package koharia.kavita

import koharia.connection.ConnectionShelfCachePolicy
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.ConcurrentHashMap

class KavitaCatalog(
    private val connectionId: Long,
    private val account: String,
    private val repository: KavitaRepository,
    val api: KavitaApiClient,
    private val onChapterChanged: suspend (Long) -> Unit = {},
    private val checkSession: () -> Unit,
) {
    private val json = api.json
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(group: String) = locks.getOrPut(group) { Mutex() }

    private suspend inline fun <reified T> cached(group: String, refresh: Boolean = false, fetch: () -> T): T =
        lock(group).withLock {
            checkSession()
            val old = repository.cache(connectionId, account, group, "data")
            if (!ConnectionShelfCachePolicy.shouldRefresh(old != null, refresh || old?.stale == true)) {
                return@withLock json.decodeFromString<T>(old!!.payload)
            }
            val value = try {
                fetch()
            } catch (error: Exception) {
                discardDenied(group, error)
                if (!refresh && old != null && isOfflineFailure(error)) {
                    return@withLock json.decodeFromString<T>(old.payload)
                }
                throw error
            }
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.putCache(
                connectionId,
                account,
                group,
                KavitaCacheEntry("data", json.encodeToString(value), System.currentTimeMillis()),
            )
            value
        }

    suspend fun libraries(refresh: Boolean = false): List<KavitaLibrary> = cached("libraries", refresh) {
        api.libraries()
    }
    suspend fun history(refresh: Boolean = false): List<KavitaHistoryEntry> = cached("history", refresh) {
        api.readingHistory()
    }
    suspend fun scrobbleStatuses(refresh: Boolean = false): List<KavitaScrobbleStatus> =
        cached("scrobbling/status", refresh) { api.get("Scrobbling/scrobble-settings") }
    suspend fun requireLibrary(id: Long) {
        if (libraries().none { it.id == id }) throw KavitaException(KavitaException.Reason.PERMISSION)
    }
    suspend fun series(
        id: Long,
        refresh: Boolean = false,
    ): KavitaSeries = cached("series/$id", refresh) { api.series(id) }
    suspend fun metadata(
        id: Long,
        refresh: Boolean = false,
    ): KavitaMetadata = cached("metadata/$id", refresh) { api.metadata(id) }
    suspend fun volumes(
        id: Long,
        refresh: Boolean = false,
    ): List<KavitaVolume> = cached("volumes/$id", refresh) {
        api.volumes(id).also { volumes -> volumes.flatMap { it.chapters }.forEach { observeContent(it) } }
    }

    suspend fun seriesBookProgress(id: Long, refresh: Boolean = false): KavitaSeriesBookProgress =
        volumes(id, refresh).bookProgress()

    suspend fun chapter(
        id: Long,
        refresh: Boolean = false,
    ): KavitaChapter = cached("chapter/$id", refresh) { api.chapter(id).also { observeContent(it) } }

    suspend fun contentVersion(id: Long): String? = repository.cache(connectionId, account, "versions", "$id")?.payload
    suspend fun cachedChapterIds(seriesId: Long?): List<Long> = if (seriesId == null) {
        repository.entries(connectionId, account, "versions").mapNotNull { it.key.toLongOrNull() }
    } else {
        repository.cache(connectionId, account, "volumes/$seriesId", "data")?.let {
            json.decodeFromString<List<KavitaVolume>>(it.payload).flatMap { volume ->
                volume.chapters.map { chapter -> chapter.id }
            }
        }.orEmpty()
    }

    private suspend fun observeContent(chapter: KavitaChapter) {
        val version = kavitaContentVersion(chapter)
        val old = contentVersion(chapter.id)
        if (old != null && old != version) {
            invalidateChapter(chapter.id)
            onChapterChanged(chapter.id)
        }
        repository.putCache(connectionId, account, "versions", KavitaCacheEntry("${chapter.id}", version, 0))
    }

    suspend fun invalidateChapter(id: Long) {
        checkSession()
        for (group in listOf("chapter/$id", "book/$id", "toc/$id")) {
            repository.cache(connectionId, account, group, "data")?.let {
                repository.putCache(connectionId, account, group, it.copy(stale = true))
            }
        }
    }
    suspend fun book(id: Long): KavitaBookInfo = cached("book/$id") { api.get("Book/$id/book-info") }
    suspend fun toc(id: Long): List<KavitaBookPart> = cached("toc/$id") { api.get("Book/$id/chapters") }
    suspend fun resource(
        path: String,
        refresh: Boolean = false,
    ): JsonElement = cached("resource/$path", refresh) { api.execute(path) }
    suspend fun postResource(path: String, body: JsonElement, refresh: Boolean = false): JsonElement =
        cached("resource/$path/" + body.toString().encodeUtf8().sha256().hex(), refresh) {
            api.execute(path, "POST", body)
        }
    suspend fun lists(refresh: Boolean = false): List<KavitaList> = cached("resource/ReadingList", refresh) {
        api.allReadingLists()
    }
    suspend fun annotationPage(page: Int, filter: JsonElement, refresh: Boolean = false): KavitaAnnotationPage =
        cached("annotation-browser/" + filter.toString().encodeUtf8().sha256().hex() + "/$page", refresh) {
            api.annotationPage(page, filter)
        }

    private fun shelfKey(filter: KavitaFilter, feed: String) =
        "shelf/" + (feed + json.encodeToString(filter)).encodeUtf8().sha256().hex()

    suspend fun cachedSeriesIds(filter: KavitaFilter): Set<Long> =
        repository.entries(connectionId, account, shelfKey(filter, "Series/all-v2"))
            .flatMap { json.decodeFromString<KavitaSeriesPage>(it.payload).items }.mapTo(hashSetOf()) { it.id }

    suspend fun page(page: Int, filter: KavitaFilter, feed: String = "Series/all-v2"): KavitaSeriesPage {
        val group = shelfKey(filter, feed)
        return lock(group).withLock {
            checkSession()
            val old = repository.cache(connectionId, account, group, page.toString())
            if (old != null && !old.stale) return@withLock json.decodeFromString(old.payload)
            val loaded = repository.entries(connectionId, account, group)
            if (loaded.any { it.stale }) {
                val staged = try {
                    fetchPages(maxOf(page, loaded.mapNotNull { it.key.toIntOrNull() }.maxOrNull() ?: 1), filter, feed)
                } catch (error: Exception) {
                    discardDenied(group, error)
                    if (old != null && isOfflineFailure(error)) {
                        return@withLock json.decodeFromString<KavitaSeriesPage>(old.payload)
                    }
                    throw error
                }
                checkSession()
                repository.replaceCache(connectionId, account, group, staged)
                return@withLock staged.firstOrNull { it.key == page.toString() }?.let {
                    json.decodeFromString<KavitaSeriesPage>(it.payload)
                } ?: KavitaSeriesPage(emptyList(), false)
            }
            val value = try {
                api.seriesPage(page, filter, feed)
            } catch (error: Exception) {
                discardDenied(group, error)
                throw error
            }
            currentCoroutineContext().ensureActive()
            checkSession()
            repository.putCache(
                connectionId,
                account,
                group,
                KavitaCacheEntry(
                    page.toString(),
                    json.encodeToString(value),
                    loaded.firstOrNull()?.generation ?: System.currentTimeMillis(),
                ),
            )
            value
        }
    }

    private suspend fun fetchPages(count: Int, filter: KavitaFilter, feed: String): List<KavitaCacheEntry> {
        val generation = System.currentTimeMillis()
        return buildList {
            for (page in 1..count) {
                val result = api.seriesPage(page, filter, feed)
                currentCoroutineContext().ensureActive()
                checkSession()
                add(KavitaCacheEntry(page.toString(), json.encodeToString(result), generation))
                if (!result.hasNext) break
            }
        }
    }

    suspend fun refresh(filter: KavitaFilter, feed: String = "Series/all-v2") {
        val group = shelfKey(filter, feed)
        lock(group).withLock {
            val count =
                repository.entries(connectionId, account, group).mapNotNull { it.key.toIntOrNull() }.maxOrNull() ?: 1
            val entries = try {
                fetchPages(count, filter, feed)
            } catch (error: Exception) {
                discardDenied(group, error)
                throw error
            }
            checkSession()
            repository.replaceCache(connectionId, account, group, entries)
        }
    }

    suspend fun invalidate() {
        checkSession()
        repository.invalidate(connectionId, account)
    }
    suspend fun invalidateGroups(vararg prefixes: String) {
        checkSession()
        prefixes.forEach { repository.invalidateGroup(connectionId, account, it) }
    }

    private suspend fun discardDenied(group: String, error: Exception) {
        if (error is KavitaException && error.status == 403) {
            checkSession()
            repository.replaceCache(connectionId, account, group, emptyList())
        }
    }

    private fun isOfflineFailure(error: Exception): Boolean = when (error) {
        is KavitaException -> error.reason == KavitaException.Reason.NETWORK
        is java.io.IOException -> true
        else -> false
    }
}
