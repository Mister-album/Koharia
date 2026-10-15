package koharia.connection

import kotlinx.coroutines.flow.Flow

/** Optional durable synchronization diagnostics; an absent record does not assert remote synchronization. */
interface ConnectionProgressSyncStatusAdapter {
    fun observeProgressSyncStatus(chapterUrls: List<String>): Flow<Map<String, Set<ConnectionProgressSyncStatus>>>
}

/** Explicit read/unread actions can be reviewed separately from reader position reconciliation. */
interface ConnectionReadStatusReviewAdapter {
    suspend fun reviewReadStatus(chapterUrl: String): ConnectionReadStatusReview?
}

class ConnectionReadStatusReview(
    val localRead: Boolean,
    val remoteRead: Boolean,
    val remoteStarted: Boolean,
    val uncertainRequest: Boolean,
    val reissue: suspend () -> Unit,
)

enum class ConnectionProgressSyncStatus {
    PENDING,
    HELD,
    CONFLICT,
    DENIED,
    VERIFYING,
    UNVERIFIED_OWNER,
    UNAVAILABLE,
    LOCAL_ONLY,
}
