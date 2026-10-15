package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterProgressOwner
import tachiyomi.domain.chapter.model.ChapterSourceUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val database: Database,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            database.transactionWithResult {
                insertChapters(chapters)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    private suspend fun insertChapters(chapters: List<Chapter>): List<Chapter> = chapters.map { chapter ->
        val chapterId = database.chaptersQueries.insertReturningId(
            chapter.mangaId,
            chapter.url,
            chapter.name,
            chapter.scanlator,
            chapter.read,
            chapter.bookmark,
            chapter.lastPageRead,
            chapter.chapterNumber,
            chapter.sourceOrder,
            chapter.dateFetch,
            chapter.dateUpload,
            chapter.version,
            chapter.memo,
        ).awaitAsOne()
        chapter.copy(id = chapterId)
    }

    override suspend fun applySourceUpdate(
        update: ChapterSourceUpdate,
        checkCurrent: suspend () -> Unit,
    ): List<Chapter>? = database.transactionWithResult {
        checkCurrent()
        val expectedById = update.expected.associateBy { it.id }
        val removedIds = update.removed.toSet()
        require(expectedById.size == update.expected.size && update.expected.all { it.mangaId == update.mangaId })
        require(removedIds.size == update.removed.size && expectedById.keys.containsAll(removedIds))
        require(update.added.all { it.mangaId == update.mangaId })
        require(update.added.map { it.url }.distinct().size == update.added.size)
        val retainedUrls = update.expected.filterNot { it.id in removedIds }.mapTo(hashSetOf()) { it.url }
        require(update.added.none { it.url in retainedUrls })
        require(update.updated.map { it.id }.distinct().size == update.updated.size)
        update.updated.forEach {
            require(it.id in expectedById && it.id !in removedIds)
            require(
                it.mangaId == null && it.url == null && it.read == null && it.bookmark == null &&
                    it.lastPageRead == null && it.version == null,
            ) {
                "Source metadata cannot replace ownership or reading state"
            }
        }
        val manga = database.mangasQueries.getMangaById(update.mangaId).awaitAsOneOrNull()
        check(manga != null && manga.source == update.sourceId && manga.url == update.mangaUrl) {
            "Source chapter ownership changed"
        }
        if (getChapterByMangaId(update.mangaId).associateBy { it.id } != expectedById) {
            return@transactionWithResult null
        }
        checkCurrent()
        if (removedIds.isNotEmpty()) database.chaptersQueries.removeChaptersWithIds(update.removed)
        val inserted = insertChapters(update.added)
        partialUpdate(*update.updated.toTypedArray())
        checkCurrent()
        inserted
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    override suspend fun updateForReader(
        updates: List<Pair<ChapterProgressOwner, ChapterUpdate>>,
        checkCurrent: suspend () -> Unit,
    ) {
        database.transaction {
            checkCurrent()
            require(updates.map { it.first.chapterId }.distinct().size == updates.size)
            updates.forEach { (owner, update) ->
                require(update.id == owner.chapterId)
                require(update.mangaId == null && update.url == null) { "Reader cannot replace chapter ownership" }
                val chapter = getChapterById(owner.chapterId)
                check(chapter != null && chapter.mangaId == owner.mangaId && chapter.url == owner.url) {
                    "Reader chapter ownership changed"
                }
                check(database.mangasQueries.getMangaById(owner.mangaId).awaitAsOneOrNull()?.source == owner.sourceId) {
                    "Reader connection ownership changed"
                }
            }
            partialUpdate(*updates.map { it.second }.toTypedArray())
            checkCurrent()
        }
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        database.transaction {
            chapterUpdates.forEach { chapterUpdate ->
                database.chaptersQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    isSyncing = 0,
                    memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            database.chaptersQueries.removeChaptersWithIds(chapterIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getChaptersByMangaIds(mangaIds: Collection<Long>): List<Chapter> {
        if (mangaIds.isEmpty()) return emptyList()
        return database.chaptersQueries
            .getChaptersByMangaIds(mangaIds.toList(), ::mapChapter)
            .awaitAsList()
    }

    override fun getChaptersByMangaIdsAsFlow(mangaIds: Collection<Long>): Flow<List<Chapter>> {
        val batches = mangaIds.distinct().chunked(900).map {
            database.chaptersQueries.getChaptersByMangaIds(it, ::mapChapter).subscribeToList()
        }
        return when (batches.size) {
            0 -> flowOf(emptyList())
            1 -> batches.single()
            else -> combine(batches) { it.flatMap { rows -> rows } }
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .awaitAsList()
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .subscribeToList()
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return database.chaptersQueries
            .getBookmarkedChaptersByMangaId(mangaId, ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return database.chaptersQueries
            .getChapterById(id, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .subscribeToList()
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return database.chaptersQueries
            .getChapterByUrlAndMangaId(url, mangaId, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getChaptersByUrlAndSourceId(url: String, sourceId: Long): List<Chapter> =
        database.chaptersQueries.getChaptersByUrlAndSourceId(url, sourceId, ::mapChapter).awaitAsList()

    @Suppress("UNUSED_PARAMETER")
    private fun mapChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        isSyncing: Long,
        memo: JsonObject,
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = memo,
    )
}
