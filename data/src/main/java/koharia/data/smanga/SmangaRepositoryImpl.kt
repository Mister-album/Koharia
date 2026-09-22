package koharia.data.smanga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import tachiyomi.data.Database

class SmangaRepositoryImpl(private val database: Database) : SmangaRepository {
    private val queries get() = database.smangaQueries

    override suspend fun cache(connectionId: Long, accountKey: String, groupKey: String, key: String) =
        queries.getCache(connectionId, accountKey, groupKey, key, ::SmangaCacheEntry).awaitAsOneOrNull()

    override suspend fun cacheGroup(connectionId: Long, accountKey: String, groupKey: String) =
        queries.getCacheGroup(connectionId, accountKey, groupKey, ::SmangaCacheEntry).awaitAsList()

    override suspend fun putCache(connectionId: Long, accountKey: String, groupKey: String, entry: SmangaCacheEntry) {
        queries.putCache(
            connectionId,
            accountKey,
            groupKey,
            entry.key,
            entry.payload,
            entry.updatedAt,
            entry.generation,
        )
    }

    override suspend fun replaceCacheGroup(
        connectionId: Long,
        accountKey: String,
        groupKey: String,
        entries: List<SmangaCacheEntry>,
    ) {
        database.transaction {
            queries.removeCacheGroup(connectionId, accountKey, groupKey)
            entries.forEach { putCache(connectionId, accountKey, groupKey, it) }
        }
    }

    override suspend fun removeConnection(connectionId: Long) {
        database.transaction {
            queries.removeConnectionCache(connectionId)
            queries.removeConnectionReadStates(connectionId)
            queries.removeConnectionHistory(connectionId)
        }
    }

    override suspend fun removeAccount(connectionId: Long, accountKey: String) {
        database.transaction {
            queries.removeAccountCache(connectionId, accountKey)
            queries.removeAccountReadStates(connectionId, accountKey)
            queries.removeAccountHistory(connectionId, accountKey)
        }
    }

    override suspend fun readState(connectionId: Long, accountKey: String, chapterId: Long) =
        queries.getReadState(connectionId, accountKey, chapterId, ::mapState).awaitAsOneOrNull()

    override suspend fun readStates(connectionId: Long, accountKey: String) =
        queries.getReadStates(connectionId, accountKey, ::mapState).awaitAsList()

    override suspend fun pendingReadStates(connectionId: Long, accountKey: String) =
        queries.getPendingReadStates(connectionId, accountKey, ::mapState).awaitAsList()

    override suspend fun readStatesForManga(connectionId: Long, accountKey: String, mangaId: Long) =
        queries.getReadStatesForManga(connectionId, accountKey, mangaId, ::mapState).awaitAsList()

    override suspend fun putReadState(connectionId: Long, accountKey: String, state: SmangaReadState) {
        queries.putReadState(
            connectionId,
            accountKey,
            state.chapterId,
            state.mangaId,
            state.pageIndex.toLong(),
            state.totalPages.toLong(),
            if (state.completed) 1 else 0,
            state.readAt,
            state.revision,
            if (state.pending) 1 else 0,
            if (state.explicitUnread) 1 else 0,
            if (state.initialPage) 1 else 0,
        )
    }

    override suspend fun acknowledgeReadState(
        connectionId: Long,
        accountKey: String,
        chapterId: Long,
        revision: Long,
    ) = queries.acknowledgeReadState(connectionId, accountKey, chapterId, revision).awaitAsOneOrNull() != null

    override suspend fun resetReadStates(connectionId: Long, accountKey: String, chapterIds: List<Long>) {
        if (chapterIds.isNotEmpty()) queries.resetReadStates(connectionId, accountKey, chapterIds)
    }

    override suspend fun enqueueHistory(connectionId: Long, accountKey: String, event: SmangaHistoryEvent) {
        require(event.status in 0..2)
        queries.enqueueHistory(
            connectionId,
            accountKey,
            event.id,
            event.mangaId,
            event.chapterId,
            event.mediaId,
            event.readAt,
            event.status.toLong(),
        )
    }

    override suspend fun historyEvents(connectionId: Long, accountKey: String) =
        queries.getHistoryEvents(connectionId, accountKey, ::mapHistory).awaitAsList()

    override suspend fun pendingHistoryEvents(connectionId: Long, accountKey: String) =
        queries.getPendingHistoryEvents(connectionId, accountKey, ::mapHistory).awaitAsList()

    override suspend fun historyEvent(connectionId: Long, accountKey: String, eventId: String) =
        queries.getHistoryEvent(connectionId, accountKey, eventId, ::mapHistory).awaitAsOneOrNull()

    override suspend fun updateHistoryStatus(connectionId: Long, accountKey: String, eventId: String, status: Int) {
        require(status in 0..2)
        queries.updateHistoryStatus(status.toLong(), connectionId, accountKey, eventId)
    }

    private fun mapState(
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

    private fun mapHistory(
        id: String,
        mangaId: Long,
        chapterId: Long,
        mediaId: Long,
        readAt: Long,
        status: Long,
    ) = SmangaHistoryEvent(id, mangaId, chapterId, mediaId, readAt, status.toInt())
}
