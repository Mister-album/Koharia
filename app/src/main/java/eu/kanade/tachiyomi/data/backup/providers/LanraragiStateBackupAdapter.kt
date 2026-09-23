package eu.kanade.tachiyomi.data.backup.providers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiStateBackupAdapter(
    private val database: Database = Injekt.get(),
) {
    suspend fun capture(mangaId: Long, chapterUrlById: Map<Long, String>): List<BackupLanraragiState> {
        val manga = database.mangasQueries.getMangaById(mangaId).awaitAsOneOrNull() ?: return emptyList()
        val connectionId = manga.source
        if (archiveId(manga.url, connectionId) == null &&
            !manga.url.startsWith("/lanraragi/$connectionId/tank/")
        ) {
            return emptyList()
        }

        val chapterUrlsById = database.chaptersQueries.getChaptersByMangaId(mangaId, 0).awaitAsList()
            .associate { it._id to it.url }
        val statesByArchive = database.lanraragiQueries.getReadStates(connectionId).awaitAsList()
            .associateBy { it.archive_id }
        return chapterUrlById.entries
            .filter { (chapterId, chapterUrl) -> chapterUrlsById[chapterId] == chapterUrl }
            .map { it.value }
            .distinct()
            .sorted()
            .mapNotNull { chapterUrl ->
                val archiveId = archiveId(chapterUrl, connectionId) ?: return@mapNotNull null
                val state = statesByArchive[archiveId] ?: return@mapNotNull null
                BackupLanraragiState(
                    chapterUrl = chapterUrl,
                    pageIndex = state.page_index.toInt(),
                    totalPages = state.total_pages.toInt(),
                    readAt = state.read_at,
                    localUnread = state.local_unread != 0L,
                    pending = state.pending != 0L,
                    initialPage = state.initial_page != 0L,
                )
            }
    }

    suspend fun restore(
        mangaId: Long,
        chapterIdByUrl: Map<String, Long>,
        states: List<BackupLanraragiState>,
    ) {
        if (states.isEmpty() || chapterIdByUrl.isEmpty()) return
        database.transaction {
            val manga = database.mangasQueries.getMangaById(mangaId).awaitAsOneOrNull() ?: return@transaction
            val connectionId = manga.source
            if (archiveId(manga.url, connectionId) == null &&
                !manga.url.startsWith("/lanraragi/$connectionId/tank/")
            ) {
                return@transaction
            }

            val chaptersByUrl = database.chaptersQueries.getChaptersByMangaId(mangaId, 0).awaitAsList()
                .associate { it.url to it._id }
            val latestReadAtByArchive = database.lanraragiQueries.getReadStates(connectionId).awaitAsList()
                .associate { it.archive_id to it.read_at }
                .toMutableMap()
            states.sortedByDescending { it.readAt }.forEach { state ->
                val chapterId = chapterIdByUrl[state.chapterUrl] ?: return@forEach
                if (chaptersByUrl[state.chapterUrl] != chapterId) return@forEach
                val archiveId = archiveId(state.chapterUrl, connectionId) ?: return@forEach
                if (state.readAt < 0 || state.totalPages < 0 || state.pageIndex < -1 ||
                    (state.totalPages > 0 && state.pageIndex >= state.totalPages)
                ) {
                    return@forEach
                }
                val localReadAt = latestReadAtByArchive[archiveId]
                if (localReadAt != null && localReadAt >= state.readAt) return@forEach
                database.lanraragiQueries.record(
                    connectionId,
                    archiveId,
                    state.pageIndex.toLong(),
                    state.totalPages.toLong(),
                    state.readAt,
                    if (state.localUnread) 1 else 0,
                    if (state.pending) 1 else 0,
                    if (state.initialPage) 1 else 0,
                )
                latestReadAtByArchive[archiveId] = state.readAt
            }
        }
    }

    private fun archiveId(url: String, connectionId: Long): String? {
        val prefix = "/lanraragi/$connectionId/archive/"
        if (!url.startsWith(prefix)) return null
        return url.removePrefix(prefix).takeIf { it.isNotEmpty() && '/' !in it }
    }
}
