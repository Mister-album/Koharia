package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupEpubProgress
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTtsProgress
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.data.backup.providers.LanraragiStateBackupAdapter
import eu.kanade.tachiyomi.data.backup.providers.SmangaStateBackupAdapter
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.domain.epub.repository.EpubBookmarkRepository
import koharia.domain.epub.repository.EpubProgressRepository
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaBackupCreator(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val epubProgressRepository: EpubProgressRepository = Injekt.get(),
    private val epubBookmarkRepository: EpubBookmarkRepository = Injekt.get(),
    private val lanraragiStateBackupAdapter: LanraragiStateBackupAdapter = LanraragiStateBackupAdapter(),
    private val smangaStateBackupAdapter: SmangaStateBackupAdapter = SmangaStateBackupAdapter(),
) {

    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        return mangas.map {
            backupManga(it, options)
        }
    }

    private suspend fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga()

        mangaObject.excludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()

        if (options.chapters) {
            // Backup all the chapters
            database.chaptersQueries
                .getChaptersByMangaId(
                    mangaId = manga.id,
                    applyScanlatorFilter = 0, // false
                    mapper = backupChapterMapper,
                )
                .awaitAsList()
                .takeUnless(List<BackupChapter>::isEmpty)
                ?.let { mangaObject.chapters = it }

            val chapterUrlsById = database.chaptersQueries.getChaptersByMangaId(manga.id, 0)
                .awaitAsList()
                .associate { it._id to it.url }
            mangaObject.epubProgress = epubProgressRepository.getProgressesByMangaId(manga.id).mapNotNull { progress ->
                chapterUrlsById[progress.chapterId]?.let { chapterUrl ->
                    BackupEpubProgress(
                        chapterUrl = chapterUrl,
                        bookUrl = progress.bookUrl,
                        locatorJson = progress.locatorJson,
                        progression = progress.progression,
                        positionIndex = progress.positionIndex,
                        updatedAt = progress.updatedAt.time,
                        lastSyncedAt = progress.lastSyncedAt?.time,
                    )
                }
            }
            mangaObject.epubBookmarks = epubBookmarkRepository.getBookmarksByMangaId(manga.id).mapNotNull { bookmark ->
                chapterUrlsById[bookmark.chapterId]?.let { chapterUrl ->
                    BackupEpubBookmark(
                        chapterUrl = chapterUrl,
                        locatorJson = bookmark.locatorJson,
                        sectionTitle = bookmark.sectionTitle,
                        progression = bookmark.progression,
                        note = bookmark.note,
                        createdAt = bookmark.createdAt.time,
                    )
                }
            }
            mangaObject.ttsProgress = database.tts_progressQueries.getByMangaId(manga.id)
                .awaitAsList()
                .mapNotNull { progress ->
                    chapterUrlsById[progress.chapter_id]?.let { chapterUrl ->
                        BackupTtsProgress(chapterUrl, progress.sentence_index, progress.updated_at.time)
                    }
                }
            mangaObject.lanraragiState = lanraragiStateBackupAdapter.capture(manga.id, chapterUrlsById)
            mangaObject.smangaState = smangaStateBackupAdapter.capture(manga.id, chapterUrlsById)
                .map { if (options.history) it else it.copy(pendingHistoryEvents = emptyList()) }
                .filter {
                    it.readState != null || it.pendingHistoryEvents.isNotEmpty() || it.confirmationPayload != null
                }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = database.manga_syncQueries
                .getTracksByMangaId(manga.id, backupTrackMapper)
                .awaitAsList()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = database.chaptersQueries
                        .getChapterById(history.chapterId)
                        .awaitAsOne()
                    BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        notes = this.notes,
        initialized = this.initialized,
        memo = MemoColumnAdapter.encode(this.memo),
    )
