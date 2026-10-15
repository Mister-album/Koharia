package koharia.domain.komga

/** A durable page event is distinct from the explicit read/unread revision containing it. */
data class KomgaPageEvent(
    val key: KomgaReadStatusKey,
    val generation: Long,
    val revision: Long,
    val page: Long,
    val total: Long,
    val readAt: Long,
    val baseline: String?,
    val phase: KomgaReadStatusPhase,
    /** A confirmed later explicit action supersedes this payload without acknowledging its page. */
    val supersededByRevision: Long? = null,
)

/** Persist before sending HTTP; retain across ambiguous responses and newer local events. */
data class KomgaPageAttempt(val event: KomgaPageEvent, val baseline: String)

data class KomgaPageState(val event: KomgaPageEvent?, val attempt: KomgaPageAttempt?) {
    val settled: Boolean get() = attempt == null &&
        (event == null || event.phase == KomgaReadStatusPhase.SYNCED || event.supersededByRevision != null)
}

interface KomgaPageEventRepository {
    /** Local journal projection only; other owners are retained as unverified, never adopted. */
    fun observeSyncStatus(
        connectionId: Long,
        ownerKey: String,
        bookIds: Collection<String>,
    ): kotlinx.coroutines.flow.Flow<Map<String, Set<KomgaProgressSyncStatus>>>

    /** Pending keys and uncertain requests from both journals, excluding terminal desired state. */
    suspend fun recoveryKeys(): List<KomgaReadStatusKey>

    /** Coherent pending payloads and uncertain requests for backup; never reusable credentials. */
    suspend fun unsettledStates(): List<KomgaPageState>
    suspend fun restoreHeld(states: List<KomgaPageState>)
    suspend fun get(key: KomgaReadStatusKey): KomgaPageEvent?
    suspend fun attempt(key: KomgaReadStatusKey): KomgaPageAttempt?
    suspend fun pending(connectionId: Long, ownerKey: String): List<KomgaPageEvent>
    suspend fun hasUnsettled(connectionId: Long, ownerKey: String): Boolean

    /** Include an explicit empty state for each requested book; do not scan unrelated owner history. */
    suspend fun states(connectionId: Long, ownerKey: String, bookIds: Collection<String>): Map<String, KomgaPageState>

    /**
     * Returns null if the captured explicit revision is no longer settled/current.
     * Unsettled events retain their original baseline, including unknown; further reading cannot adopt remote changes.
     */
    suspend fun record(
        key: KomgaReadStatusKey,
        revision: Long,
        page: Long,
        total: Long,
        readAt: Long,
        baseline: String?,
        checkOwner: () -> Unit,
    ): KomgaPageEvent?

    /** Accept a compared snapshot only while its local state is unchanged and no request is uncertain. */
    suspend fun reconcileLocal(
        key: KomgaReadStatusKey,
        revision: Long,
        expected: KomgaPageState,
        page: Long,
        total: Long,
        readAt: Long,
        baseline: String,
        keepLocal: Boolean,
        checkOwner: () -> Unit,
    ): KomgaPageEvent?

    /** A newer event cannot replace an unresolved attempt, even after process recreation. */
    suspend fun begin(
        event: KomgaPageEvent,
        baseline: String,
        checkOwner: () -> Unit,
    ): KomgaPageAttempt?

    /** Acknowledge only this attempt. Success may advance a newer event's baseline, never finish it. */
    suspend fun settle(
        attempt: KomgaPageAttempt,
        phase: KomgaReadStatusPhase,
        confirmedBaseline: String?,
        checkOwner: () -> Unit,
    ): Boolean

    /** Change only the captured event; uncertain attempts remain available for reconciliation. */
    suspend fun transition(
        event: KomgaPageEvent,
        phase: KomgaReadStatusPhase,
        checkOwner: () -> Unit,
    ): Boolean

    /** Preserve uncertain attempts for recovery; connection lifecycle must not silently adopt them. */
    suspend fun holdConnection(connectionId: Long)
    suspend fun removeConnection(connectionId: Long)
}

enum class KomgaProgressSyncStatus { PENDING, HELD, CONFLICT, DENIED, VERIFYING, UNVERIFIED_OWNER }
