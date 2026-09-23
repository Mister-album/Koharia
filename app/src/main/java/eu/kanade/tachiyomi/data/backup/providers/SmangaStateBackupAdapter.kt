package eu.kanade.tachiyomi.data.backup.providers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Backs up Smanga's provider state using chapter URLs instead of local database IDs. */
class SmangaStateBackupAdapter(private val database: Database = Injekt.get()) {
    private val queries get() = database.smangaQueries

    suspend fun capture(mangaId: Long, chapterUrlById: Map<Long, String>): List<BackupSmangaState> {
        val manga = mangaIdentity(mangaId) ?: return emptyList()
        val storedUrls = database.chaptersQueries.getChaptersByMangaId(mangaId, 0).awaitAsList()
            .associate { it._id to it.url }
        val urls = chapterUrlById.mapNotNull { (chapterId, url) ->
            if (storedUrls[chapterId] != url) return@mapNotNull null
            parseChapterUrl(url)?.takeIf { it.manga == manga }?.let { chapterId to url }
        }.toMap()
        if (urls.isEmpty()) return emptyList()

        val states = queries.getReadStatesForManga(
            manga.connectionId,
            manga.accountKey,
            manga.remoteMangaId,
            ::mapReadState,
        ).awaitAsList().associateBy(SmangaReadState::chapterId)
        val events = queries.getHistoryEvents(
            manga.connectionId,
            manga.accountKey,
            ::mapHistoryEvent,
        ).awaitAsList()
            .filter { it.mangaId == manga.remoteMangaId && it.status <= HISTORY_ATTEMPTED.toInt() }
            .groupBy(SmangaHistoryEvent::chapterId)
        val confirmations = queries.getCacheGroup(
            manga.connectionId,
            manga.accountKey,
            CONFIRMATION_GROUP,
        ).awaitAsList().associate { it.cache_key to it.payload }

        return urls.mapNotNull { (_, url) ->
            val chapter = parseChapterUrl(url) ?: return@mapNotNull null
            val state = states[chapter.remoteChapterId]
            val pendingEvents = events[chapter.remoteChapterId].orEmpty()
                .filter { it.mediaId == chapter.mediaId }
            val confirmationPayload = confirmations[chapter.remoteChapterId.toString()]
            if (state == null && pendingEvents.isEmpty() && confirmationPayload == null) return@mapNotNull null
            BackupSmangaState(
                chapterUrl = url,
                readState = state?.let {
                    BackupSmangaReadState(
                        it.pageIndex,
                        it.totalPages,
                        it.completed,
                        it.readAt,
                        it.revision,
                        it.pending,
                        it.explicitUnread,
                        it.initialPage,
                    )
                },
                pendingHistoryEvents = pendingEvents.map {
                    BackupSmangaHistoryEvent(it.id, it.mediaId, it.readAt)
                },
                confirmationPayload = confirmationPayload,
            )
        }.sortedBy(BackupSmangaState::chapterUrl)
    }

    suspend fun restore(mangaId: Long, chapterIdByUrl: Map<String, Long>, states: List<BackupSmangaState>) {
        if (states.isEmpty()) return
        val manga = mangaIdentity(mangaId) ?: return
        database.transaction {
            val storedChapterIds = database.chaptersQueries.getChaptersByMangaId(mangaId, 0).awaitAsList()
                .associate { it.url to it._id }
            states.forEach { item ->
                val localChapterId = chapterIdByUrl[item.chapterUrl] ?: return@forEach
                if (storedChapterIds[item.chapterUrl] != localChapterId) return@forEach
                val chapter = parseChapterUrl(item.chapterUrl)?.takeIf { it.manga == manga }
                    ?: return@forEach
                if (item.confirmationPayload != null &&
                    queries.getCache(
                        manga.connectionId,
                        manga.accountKey,
                        CONFIRMATION_GROUP,
                        chapter.remoteChapterId.toString(),
                    ).awaitAsOneOrNull() == null
                ) {
                    queries.putCache(
                        manga.connectionId,
                        manga.accountKey,
                        CONFIRMATION_GROUP,
                        chapter.remoteChapterId.toString(),
                        item.confirmationPayload,
                        System.currentTimeMillis(),
                        0L,
                    )
                }
                val backupState = item.readState
                if (backupState != null && backupState.isValid()) {
                    val current = queries.getReadState(
                        manga.connectionId,
                        manga.accountKey,
                        chapter.remoteChapterId,
                        ::mapReadState,
                    ).awaitAsOneOrNull()
                    if (current == null || backupState.readAt > current.readAt) {
                        queries.putReadState(
                            manga.connectionId,
                            manga.accountKey,
                            chapter.remoteChapterId,
                            manga.remoteMangaId,
                            backupState.pageIndex.toLong(),
                            backupState.totalPages.toLong(),
                            if (backupState.completed) 1L else 0L,
                            backupState.readAt,
                            maxOf(current?.revision ?: 0L, backupState.revision)
                                .coerceAtMost(Long.MAX_VALUE - 1) + 1,
                            if (backupState.pending) 1L else 0L,
                            if (backupState.explicitUnread) 1L else 0L,
                            if (backupState.initialPage) 1L else 0L,
                        )
                    }
                }
                item.pendingHistoryEvents.forEach eventLoop@{ event ->
                    if (event.eventId.isBlank() || event.mediaId != chapter.mediaId || event.readAt < 0L) {
                        return@eventLoop
                    }
                    queries.enqueueHistory(
                        manga.connectionId,
                        manga.accountKey,
                        event.eventId,
                        manga.remoteMangaId,
                        chapter.remoteChapterId,
                        event.mediaId,
                        event.readAt,
                        HISTORY_ATTEMPTED,
                    )
                }
            }
        }
    }

    private suspend fun mangaIdentity(mangaId: Long): MangaIdentity? {
        val manga = database.mangasQueries.getMangaById(mangaId).awaitAsOneOrNull() ?: return null
        return parseMangaUrl(manga.url)?.takeIf { it.connectionId == manga.source }
    }

    private fun BackupSmangaReadState.isValid() =
        pageIndex >= -1 && totalPages >= 0 && readAt >= 0 && revision >= 0 &&
            (totalPages == 0 || pageIndex < totalPages)

    private fun parseMangaUrl(url: String): MangaIdentity? {
        val match = MANGA_URL.matchEntire(url) ?: return null
        return MangaIdentity(
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null,
            match.groupValues[2],
            match.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: return null,
        )
    }

    private fun parseChapterUrl(url: String): ChapterIdentity? {
        val match = CHAPTER_URL.matchEntire(url) ?: return null
        return ChapterIdentity(
            MangaIdentity(
                match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null,
                match.groupValues[2],
                match.groupValues[4].toLongOrNull()?.takeIf { it > 0 } ?: return null,
            ),
            match.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: return null,
            match.groupValues[5].toLongOrNull()?.takeIf { it > 0 } ?: return null,
        )
    }

    private data class MangaIdentity(val connectionId: Long, val accountKey: String, val remoteMangaId: Long)
    private data class ChapterIdentity(val manga: MangaIdentity, val mediaId: Long, val remoteChapterId: Long)

    private companion object {
        const val CONFIRMATION_GROUP = "reading-confirmation"
        const val HISTORY_ATTEMPTED = 1L
        val MANGA_URL = Regex("^/smanga/([0-9]+)/([^/]+)/manga/([0-9]+)$")
        val CHAPTER_URL = Regex(
            "^/smanga/([0-9]+)/([^/]+)/chapter/([0-9]+)/([0-9]+)/([0-9]+)\\.(?:pdf|pages)$",
        )

        fun mapReadState(
            chapterId: Long,
            mangaId: Long,
            pageIndex: Long,
            totalPages: Long,
            completed: Long,
            readAt: Long,
            revision: Long,
            pending: Long,
            explicitUnread: Long,
            initialPage: Long,
        ) = SmangaReadState(
            chapterId,
            mangaId,
            pageIndex.toInt(),
            totalPages.toInt(),
            completed != 0L,
            readAt,
            revision,
            pending != 0L,
            explicitUnread != 0L,
            initialPage != 0L,
        )

        fun mapHistoryEvent(
            eventId: String,
            mangaId: Long,
            chapterId: Long,
            mediaId: Long,
            readAt: Long,
            status: Long,
        ) = SmangaHistoryEvent(eventId, mangaId, chapterId, mediaId, readAt, status.toInt())
    }
}
