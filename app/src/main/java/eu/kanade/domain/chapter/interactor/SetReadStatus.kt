package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import koharia.connection.ConnectionReadStatus
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReadStatusCommitAdapter
import koharia.connection.readStatusFailure
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
) {

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0 else null,
            id = chapter.id,
        )
    }

    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = withNonCancellableContext {
        val requested = chapters.distinctBy(Chapter::id)
        if (requested.isEmpty()) return@withNonCancellableContext Result.NoChapters
        val adapters = requested.map { it.mangaId }.distinct().associateWith { mangaId ->
            runCatching {
                val manga = mangaRepository.getMangaById(mangaId)
                sourceManager.get(manga.source) as? ConnectionReadStatusAdapter
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to resolve read-status owner mangaId=$mangaId" }
            }.getOrNull()
        }
        val chaptersToUpdate = requested.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0
            }
        }
        try {
            val localUpdates = chaptersToUpdate.filterNot {
                adapters[it.mangaId] is ConnectionReadStatusCommitAdapter
            }
            if (localUpdates.isNotEmpty()) {
                chapterRepository.updateAll(localUpdates.map { mapper(it, read) })
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        // Repeating an explicit action must retry failed synchronization even when the local flag already matches.
        val outcomes = pushConnectionReadStatus(requested, read, adapters)

        if (read && downloadPreferences.removeAfterMarkedAsRead.get()) {
            chaptersToUpdate
                .filter { outcomes[it.id]?.allowsDownloadDeletion == true }
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    deleteDownload.awaitAll(
                        manga = mangaRepository.getMangaById(mangaId),
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        Result.Completed(outcomes)
    }

    private suspend fun pushConnectionReadStatus(
        chapters: List<Chapter>,
        read: Boolean,
        adapters: Map<Long, ConnectionReadStatusAdapter?>,
    ): Map<Long, ConnectionReadStatus> {
        val outcomes = chapters.associate { it.id to ConnectionReadStatus.UNAVAILABLE }.toMutableMap()
        chapters
            .groupBy { it.mangaId }
            .forEach { (mangaId, mangaChapters) ->
                val adapter = adapters[mangaId] ?: return@forEach

                mangaChapters.forEach { chapter ->
                    outcomes[chapter.id] = runCatching {
                        if (adapter is ConnectionReadStatusCommitAdapter) {
                            adapter.commitChapterReadStatus(chapter, read)
                        } else {
                            adapter.setChapterReadStatus(chapter.url, read)
                        }
                    }.onFailure { error ->
                        logcat(LogPriority.WARN, error) {
                            "Failed to push connection read status for chapterId=${chapter.id}"
                        }
                    }.getOrElse { it.readStatusFailure() }
                }
            }
        return outcomes
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) =
        await(manga.id, read)

    sealed interface Result {
        data class Completed(val chapters: Map<Long, ConnectionReadStatus>) : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
