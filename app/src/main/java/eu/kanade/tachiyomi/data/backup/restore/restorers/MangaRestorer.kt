package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupEpubProgress
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTtsProgress
import eu.kanade.tachiyomi.data.backup.providers.BackupLanraragiState
import eu.kanade.tachiyomi.data.backup.providers.BackupSmangaState
import eu.kanade.tachiyomi.data.backup.providers.LanraragiStateBackupAdapter
import eu.kanade.tachiyomi.data.backup.providers.SmangaStateBackupAdapter
import koharia.domain.epub.repository.EpubBookmarkRepository
import koharia.domain.epub.repository.EpubProgressRepository
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.max

class MangaRestorer(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val epubProgressRepository: EpubProgressRepository = Injekt.get(),
    private val epubBookmarkRepository: EpubBookmarkRepository = Injekt.get(),
    private val lanraragiStateBackupAdapter: LanraragiStateBackupAdapter = LanraragiStateBackupAdapter(),
    private val smangaStateBackupAdapter: SmangaStateBackupAdapter = SmangaStateBackupAdapter(),
    fetchInterval: FetchInterval = Injekt.get(),
) {

    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = database.mangasQueries
            .getAllMangaSourceAndUrl()
            .awaitAsList()
            .groupBy({ it.source }, { it.url })

        return backupMangas
            .sortedWith(
                compareBy<BackupManga> { it.url in urlsBySource[it.source].orEmpty() }
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
    ) {
        database.transaction {
            val dbManga = findExistingManga(backupManga)
            val manga = backupManga.getMangaImpl()
            val restoredManga = if (dbManga == null) {
                restoreNewManga(manga)
            } else {
                restoreExistingManga(manga, dbManga)
            }

            restoreMangaDetails(
                manga = restoredManga,
                chapters = backupManga.chapters,
                categories = backupManga.categories,
                backupCategories = backupCategories,
                history = backupManga.history,
                tracks = backupManga.tracking,
                excludedScanlators = backupManga.excludedScanlators,
                epubProgress = backupManga.epubProgress,
                epubBookmarks = backupManga.epubBookmarks,
                ttsProgress = backupManga.ttsProgress,
                lanraragiState = backupManga.lanraragiState,
                smangaState = backupManga.smangaState,
            )
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id))
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id))
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            author = newer.author,
            artist = newer.artist,
            description = newer.description,
            genre = newer.genre,
            thumbnailUrl = newer.thumbnailUrl,
            status = newer.status,
            initialized = this.initialized || newer.initialized,
            version = newer.version,
        )
    }

    private suspend fun updateManga(manga: Manga): Manga {
        database.mangasQueries.update(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre?.joinToString(separator = ", "),
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = null,
            calculateInterval = null,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            mangaId = manga.id,
            updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
            version = manga.version,
            isSyncing = 1,
            notes = manga.notes,
            memo = manga.memo.let(MemoColumnAdapter::encode),
        )
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        return manga.copy(
            id = insertManga(manga),
        )
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull {
                val chapter = it.toChapterImpl().copy(mangaId = manga.id)

                val dbChapter = dbChaptersByUrl[chapter.url]
                    ?: // New chapter
                    return@mapNotNull chapter

                if (chapter.forComparison() == dbChapter.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing chapter
                var updatedChapter = chapter
                    .copyFrom(dbChapter)
                    .copy(
                        id = dbChapter.id,
                        bookmark = chapter.bookmark || dbChapter.bookmark,
                    )
                if (dbChapter.read && !updatedChapter.read) {
                    updatedChapter = updatedChapter.copy(
                        read = true,
                        lastPageRead = dbChapter.lastPageRead,
                    )
                } else if (updatedChapter.lastPageRead == 0L && dbChapter.lastPageRead != 0L) {
                    updatedChapter = updatedChapter.copy(
                        lastPageRead = dbChapter.lastPageRead,
                    )
                }
                updatedChapter
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    private fun Chapter.forComparison() =
        this.copy(id = 0L, mangaId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L)

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.insert(
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
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    version = chapter.version,
                    isSyncing = 0,
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return database.mangasQueries.insertReturningId(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = 0L,
            calculateInterval = 0L,
            initialized = manga.initialized,
            viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy,
            version = manga.version,
            notes = manga.notes,
            memo = manga.memo,
        )
            .awaitAsOne()
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
        epubProgress: List<BackupEpubProgress>,
        epubBookmarks: List<BackupEpubBookmark>,
        ttsProgress: List<BackupTtsProgress>,
        lanraragiState: List<BackupLanraragiState>,
        smangaState: List<BackupSmangaState>,
    ): Manga {
        restoreCategories(manga, categories, backupCategories)
        restoreChapters(manga, chapters)
        restoreEpubState(manga, epubProgress, epubBookmarks)
        restoreTtsProgress(manga, ttsProgress)
        if (lanraragiState.isNotEmpty() || smangaState.isNotEmpty()) {
            val chapterIdByUrl = restoredChapterIdsByUrl(manga.id)
            lanraragiStateBackupAdapter.restore(manga.id, chapterIdByUrl, lanraragiState)
            smangaStateBackupAdapter.restore(manga.id, chapterIdByUrl, smangaState)
        }
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        updateManga.awaitUpdateFetchInterval(manga, now, currentFetchWindow)
        return manga
    }

    private suspend fun restoreEpubState(
        manga: Manga,
        progress: List<BackupEpubProgress>,
        bookmarks: List<BackupEpubBookmark>,
    ) {
        if (progress.isEmpty() && bookmarks.isEmpty()) return
        val chaptersByUrl = restoredChapterIdsByUrl(manga.id)
        val existingProgress = epubProgressRepository.getProgressesByMangaId(manga.id)
            .associateBy { it.chapterId }
        progress.forEach { saved ->
            val chapterId = chaptersByUrl[saved.chapterUrl] ?: return@forEach
            if ((existingProgress[chapterId]?.updatedAt?.time ?: Long.MIN_VALUE) >= saved.updatedAt) return@forEach
            database.epub_progressQueries.upsert(
                chapterId = chapterId,
                mangaId = manga.id,
                bookUrl = saved.bookUrl,
                locatorJson = saved.locatorJson,
                progression = saved.progression,
                positionIndex = saved.positionIndex,
                updatedAt = Date(saved.updatedAt),
                lastSyncedAt = saved.lastSyncedAt?.let(::Date),
            )
        }
        val existingBookmarks = epubBookmarkRepository.getBookmarksByMangaId(manga.id)
            .associateBy { it.chapterId to (it.locatorJson to it.createdAt.time) }
        bookmarks.forEach { saved ->
            val chapterId = chaptersByUrl[saved.chapterUrl] ?: return@forEach
            val existing = existingBookmarks[chapterId to (saved.locatorJson to saved.createdAt)]
            if (existing != null) {
                if (existing.note != saved.note) database.epub_bookmarkQueries.updateNote(saved.note, existing.id)
                return@forEach
            }
            database.epub_bookmarkQueries.insert(
                chapterId = chapterId,
                mangaId = manga.id,
                locatorJson = saved.locatorJson,
                sectionTitle = saved.sectionTitle,
                progression = saved.progression,
                note = saved.note,
                createdAt = Date(saved.createdAt),
            )
        }
    }

    private suspend fun restoreTtsProgress(manga: Manga, progress: List<BackupTtsProgress>) {
        if (progress.isEmpty()) return
        val chaptersByUrl = restoredChapterIdsByUrl(manga.id)
        val existing = database.tts_progressQueries.getByMangaId(manga.id)
            .awaitAsList()
            .associateBy { it.chapter_id }
        progress.forEach { saved ->
            val chapterId = chaptersByUrl[saved.chapterUrl] ?: return@forEach
            if ((existing[chapterId]?.updated_at?.time ?: Long.MIN_VALUE) >= saved.updatedAt) return@forEach
            database.tts_progressQueries.upsert(chapterId, manga.id, saved.sentenceIndex, Date(saved.updatedAt))
        }
    }

    private suspend fun restoredChapterIdsByUrl(mangaId: Long): Map<String, Long> =
        database.chaptersQueries.getChaptersByMangaId(mangaId, 0)
            .awaitAsList()
            .associate { it.url to it._id }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getCategories.await()
        val dbCategoriesByName = dbCategories.associateBy { it.name }

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val mangaCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                    Pair(manga.id, dbCategory.id)
                }
            }
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            database.transaction {
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    database.mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        val chaptersByUrl = restoredChapterIdsByUrl(manga.id)
        val historyByChapterId = database.historyQueries.getHistoryByMangaId(manga.id)
            .awaitAsList()
            .associateBy { it.chapter_id }
        val toUpdate = backupHistory.mapNotNull { history ->
            val chapterId = chaptersByUrl[history.url] ?: return@mapNotNull null
            val dbHistory = historyByChapterId[chapterId]
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                return@mapNotNull item.copy(chapterId = chapterId)
            }

            // Update history entry
            item.copy(
                id = dbHistory._id,
                chapterId = dbHistory.chapter_id,
                readAt = max(item.readAt?.time ?: 0L, dbHistory.last_read?.time ?: 0L)
                    .takeIf { it > 0L }
                    ?.let { Date(it) },
                readDuration = max(item.readDuration, dbHistory.time_read) - dbHistory.time_read,
            )
        }

        if (toUpdate.isEmpty()) return
        database.transaction {
            toUpdate.forEach {
                database.historyQueries.upsert(
                    it.chapterId,
                    it.readAt,
                    it.readDuration,
                )
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }

        if (existingTracks.isEmpty()) return
        database.transaction {
            existingTracks.forEach { track ->
                database.manga_syncQueries.update(
                    track.mangaId,
                    track.trackerId,
                    track.remoteId,
                    track.libraryId,
                    track.title,
                    track.lastChapterRead,
                    track.totalChapters,
                    track.status,
                    track.score,
                    track.remoteUrl,
                    track.startDate,
                    track.finishDate,
                    track.private,
                    track.id,
                )
            }
        }
    }

    private fun Track.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()
        val toInsert = excludedScanlators.filter { it !in existingExcludedScanlators }
        if (toInsert.isEmpty()) return
        toInsert.forEach { database.excluded_scanlatorsQueries.insert(manga.id, it) }
    }
}
