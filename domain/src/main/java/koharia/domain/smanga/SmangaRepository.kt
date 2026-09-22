package koharia.domain.smanga

data class SmangaCacheEntry(
    val key: String,
    val payload: String,
    val updatedAt: Long,
    val generation: Long = updatedAt,
)

data class SmangaReadState(
    val chapterId: Long,
    val mangaId: Long,
    val pageIndex: Int,
    val totalPages: Int,
    val completed: Boolean,
    val readAt: Long,
    val revision: Long,
    val pending: Boolean,
    val explicitUnread: Boolean = false,
    val initialPage: Boolean = false,
)

data class SmangaHistoryEvent(
    val id: String,
    val mangaId: Long,
    val chapterId: Long,
    val mediaId: Long,
    val readAt: Long,
    val status: Int = 0,
)

interface SmangaRepository {
    suspend fun cache(connectionId: Long, accountKey: String, groupKey: String, key: String): SmangaCacheEntry?
    suspend fun cacheGroup(connectionId: Long, accountKey: String, groupKey: String): List<SmangaCacheEntry>
    suspend fun putCache(connectionId: Long, accountKey: String, groupKey: String, entry: SmangaCacheEntry)
    suspend fun replaceCacheGroup(
        connectionId: Long,
        accountKey: String,
        groupKey: String,
        entries: List<SmangaCacheEntry>,
    )
    suspend fun removeConnection(connectionId: Long)
    suspend fun removeAccount(connectionId: Long, accountKey: String)
    suspend fun readState(connectionId: Long, accountKey: String, chapterId: Long): SmangaReadState?
    suspend fun readStates(connectionId: Long, accountKey: String): List<SmangaReadState>
    suspend fun pendingReadStates(connectionId: Long, accountKey: String): List<SmangaReadState> =
        readStates(connectionId, accountKey).filter { it.pending }
    suspend fun readStatesForManga(connectionId: Long, accountKey: String, mangaId: Long): List<SmangaReadState> =
        readStates(connectionId, accountKey).filter { it.mangaId == mangaId }
    suspend fun putReadState(connectionId: Long, accountKey: String, state: SmangaReadState)
    suspend fun acknowledgeReadState(connectionId: Long, accountKey: String, chapterId: Long, revision: Long): Boolean
    suspend fun resetReadStates(connectionId: Long, accountKey: String, chapterIds: List<Long>)
    suspend fun enqueueHistory(connectionId: Long, accountKey: String, event: SmangaHistoryEvent)
    suspend fun historyEvents(connectionId: Long, accountKey: String): List<SmangaHistoryEvent>
    suspend fun pendingHistoryEvents(connectionId: Long, accountKey: String): List<SmangaHistoryEvent> =
        historyEvents(connectionId, accountKey).filter { it.status == 0 }
    suspend fun historyEvent(connectionId: Long, accountKey: String, eventId: String): SmangaHistoryEvent? =
        historyEvents(connectionId, accountKey).firstOrNull { it.id == eventId }
    suspend fun updateHistoryStatus(connectionId: Long, accountKey: String, eventId: String, status: Int)
}
