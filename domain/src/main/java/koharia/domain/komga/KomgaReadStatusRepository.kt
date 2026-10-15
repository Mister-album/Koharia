package koharia.domain.komga

import kotlinx.coroutines.flow.Flow

data class KomgaReadStatusKey(val connectionId: Long, val ownerKey: String, val bookId: String) {
    init {
        require(ownerKey.isNotBlank())
        require(bookId.isNotBlank())
    }
}

enum class KomgaReadStatusPhase { PENDING, SYNCED, CONFLICT, DENIED, HELD }

data class KomgaReadStatusIntent(
    val key: KomgaReadStatusKey,
    val revision: Long,
    val read: Boolean,
    val requestedAt: Long,
    val baseline: String?,
    val phase: KomgaReadStatusPhase,
)

/** An uncertain HTTP request survives replacement of the user's desired read/unread intent. */
data class KomgaReadStatusAttempt(val intent: KomgaReadStatusIntent, val baseline: String)

/** The adapter resolves book identity; the repository verifies the exact chapter snapshot and source ownership. */
data class KomgaReadStatusChapter(val id: Long, val url: String)

interface KomgaReadStatusRepository {
    suspend fun get(key: KomgaReadStatusKey): KomgaReadStatusIntent?
    fun observe(key: KomgaReadStatusKey): Flow<KomgaReadStatusIntent?>
    suspend fun pending(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent>
    suspend fun forOwner(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent>
    suspend fun unsettled(): List<KomgaReadStatusIntent>

    suspend fun attempt(key: KomgaReadStatusKey): KomgaReadStatusAttempt?
    suspend fun attempts(connectionId: Long, ownerKey: String): List<KomgaReadStatusAttempt>
    suspend fun allAttempts(): List<KomgaReadStatusAttempt>
    suspend fun restoreHeldAttempts(attempts: List<KomgaReadStatusAttempt>)
    suspend fun attemptsForBooks(
        connectionId: Long,
        ownerKey: String,
        bookIds: Collection<String>,
    ): Map<String, KomgaReadStatusAttempt?>

    /** Persist before HTTP; never replace an unresolved request with a newer intent. */
    suspend fun begin(
        intent: KomgaReadStatusIntent,
        baseline: String,
        checkOwner: () -> Unit,
    ): KomgaReadStatusAttempt?

    /** Confirmation of an old request cannot finish a newer desired revision. */
    suspend fun settle(
        attempt: KomgaReadStatusAttempt,
        phase: KomgaReadStatusPhase,
        confirmedBaseline: String?,
        checkOwner: () -> Unit,
    ): Boolean

    suspend fun transition(
        intent: KomgaReadStatusIntent,
        phase: KomgaReadStatusPhase,
        checkOwner: () -> Unit,
    ): Boolean

    /** Import into an isolated owner namespace, without adopting credentials or changing chapter state. */
    suspend fun restoreHeld(intents: List<KomgaReadStatusIntent>)

    /** Atomically store the new intent and update every supplied local alias; an unindexed book has no aliases. */
    suspend fun record(
        key: KomgaReadStatusKey,
        read: Boolean,
        requestedAt: Long,
        baseline: String?,
        chapters: List<KomgaReadStatusChapter>,
        checkOwner: () -> Unit,
    ): KomgaReadStatusIntent

    /** Only the foreground action that created this unknown-baseline intent may complete its comparison. */
    suspend fun prepareBaseline(
        intent: KomgaReadStatusIntent,
        baseline: String,
        checkOwner: () -> Unit,
    ): Boolean

    /** Only the current pending revision may finish. Terminal intents require a new explicit action to retry. */
    suspend fun finish(key: KomgaReadStatusKey, revision: Long, phase: KomgaReadStatusPhase): Boolean
    suspend fun holdConnection(connectionId: Long)
    suspend fun removeConnection(connectionId: Long)
}
