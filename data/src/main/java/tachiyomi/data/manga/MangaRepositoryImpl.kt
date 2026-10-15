package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

class MangaRepositoryImpl(
    private val database: Database,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .awaitAsOne()
    }

    override suspend fun getMangaByIdOrNull(id: Long): Manga? {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .subscribeToOne()
    }

    override suspend fun getMangaByIdAsFlowOrNull(id: Long): Flow<Manga?> {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .subscribeToOneOrNull()
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return database.mangasQueries
            .getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return database.mangasQueries
            .getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
            .subscribeToOneOrNull()
    }

    override fun getMangaBySourceIdAsFlow(sourceId: Long): Flow<List<Manga>> {
        return database.mangasQueries
            .getMangaBySourceId(sourceId, MangaMapper::mapManga)
            .subscribeToList()
    }

    override fun getMangaByUrlsAndSourceIdAsFlow(urls: Collection<String>, sourceId: Long): Flow<List<Manga>> {
        val batches = urls.distinct().chunked(900).map {
            database.mangasQueries.getMangaByUrlsAndSourceId(it, sourceId, MangaMapper::mapManga).subscribeToList()
        }
        return when (batches.size) {
            0 -> flowOf(emptyList())
            1 -> batches.single()
            else -> combine(batches) { it.flatMap { rows -> rows } }
        }
    }

    override suspend fun getMangaBySourceId(sourceId: Long): List<Manga> {
        return database.mangasQueries
            .getMangaBySourceId(sourceId, MangaMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getFavorites(): List<Manga> {
        return database.mangasQueries
            .getFavorites(MangaMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return database.mangasQueries
            .getReadMangaNotInLibrary(MangaMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return database.libraryViewQueries
            .library(MangaMapper::mapLibraryManga)
            .awaitAsList()
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return database.libraryViewQueries
            .library(MangaMapper::mapLibraryManga)
            .subscribeToList()
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return database.mangasQueries
            .getFavoriteBySourceId(sourceId, MangaMapper::mapManga)
            .subscribeToList()
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return database.mangasQueries
            .getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
            .awaitAsList()
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            database.mangasQueries.resetViewerFlags()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        database.transaction {
            database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.forEach { categoryId ->
                database.mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateFromSource(
        manga: Manga,
        update: MangaUpdate,
        checkCurrent: suspend () -> Unit,
    ): Boolean = database.transactionWithResult {
        checkCurrent()
        require(update.id == manga.id && update.source == null && update.url == null)
        val current = getMangaByIdOrNull(manga.id) ?: return@transactionWithResult false
        check(current.source == manga.source && current.url == manga.url) { "Manga source ownership changed" }
        partialUpdate(update)
        checkCurrent()
        true
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return insertNetworkManga(manga) {}
    }

    override suspend fun insertNetworkManga(manga: List<Manga>, checkCurrent: suspend () -> Unit): List<Manga> {
        return database.transactionWithResult {
            checkCurrent()
            manga.map {
                val stored = database.mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    memo = it.memo,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapManga,
                )
                    .awaitAsOne()
                refreshShelfUnitCount(stored, it.knownShelfUnitCount())
            }.also { checkCurrent() }
        }
    }

    override suspend fun refreshShelfUnitCounts(manga: List<Manga>, checkCurrent: suspend () -> Unit) {
        database.transaction {
            checkCurrent()
            for ((sourceId, entries) in manga.groupBy { it.source }) {
                val counts = entries.mapNotNull { entry ->
                    entry.knownShelfUnitCount()?.let { entry.url to it }
                }.toMap()
                for (urls in counts.keys.chunked(200)) {
                    val stored = database.mangasQueries.getMangaByUrlsAndSourceId(urls, sourceId, MangaMapper::mapManga)
                        .awaitAsList()
                    checkCurrent()
                    stored.forEach { refreshShelfUnitCount(it, counts[it.url]) }
                }
            }
            checkCurrent()
        }
    }

    private fun Manga.knownShelfUnitCount() = (memo["booksCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

    private suspend fun refreshShelfUnitCount(stored: Manga, count: Int?): Manga {
        if (count == null || stored.knownShelfUnitCount() == count) return stored
        // Catalogue totals stay live for favorites without replacing their user metadata.
        val updated = stored.copy(memo = JsonObject(stored.memo + ("booksCount" to JsonPrimitive(count))))
        database.mangasQueries.updateShelfMemo(updated.memo, stored.id, stored.source, stored.url)
        return updated
    }

    override suspend fun deleteMangaBySourceId(sourceId: Long) {
        database.mangasQueries.deleteMangaBySourceId(sourceId)
    }

    override suspend fun deleteMangaById(mangaId: Long) {
        database.mangasQueries.deleteMangaById(mangaId)
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        database.transaction {
            mangaUpdates.forEach { value ->
                database.mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    calculateInterval = value.fetchInterval?.toLong(),
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    mangaId = value.id,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    memo = value.memo?.let(MemoColumnAdapter::encode),
                )
            }
        }
    }
}
