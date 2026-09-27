package koharia.kavita

import koharia.connection.ConnectionRestoreState
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.time.Instant

class KavitaReadingCoordinator(
    private val connectionId: Long,
    private val account: String,
    private val repository: KavitaRepository,
    private val api: KavitaApiClient,
    private val scope: CoroutineScope,
    private val checkSession: () -> Unit,
    private val apply: suspend (KavitaReadingState) -> Unit,
) {
    private val mutex = Mutex()
    private val flushMutex = Mutex()
    private val baselines = mutableMapOf<Long, KavitaProgress>()
    private var job: Job? = null
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restoring = false
    private val json get() = api.json

    private suspend fun operation(id: Long) = repository.operations(connectionId, account).firstOrNull {
        it.key ==
            "progress/$id"
    }
    suspend fun cached(id: Long): KavitaReadingState? = operation(id)?.let { json.decodeFromString(it.payload) }
    suspend fun hasPending(id: Long): Boolean = operation(id)?.pending == true

    suspend fun record(
        ref: KavitaChapterRef,
        page: Int,
        total: Int,
        readAt: Long,
        initial: Boolean = false,
        explicit: Boolean = false,
        anchor: String? = null,
        unread: Boolean = false,
    ) {
        if (ConnectionRestoreState.isRestoring || restoring || total <= 0 || page !in 0..total) return
        mutex.withLock {
            checkSession()
            val old = operation(ref.chapterId)
            val previous = old?.let { json.decodeFromString<KavitaReadingState>(it.payload) }
            if (initial && !explicit) return@withLock
            if (!explicit && previous != null && kavitaTimestamp(previous.progress.lastModifiedUtc) > readAt) {
                return@withLock
            }
            val progress =
                KavitaProgress(
                    ref.libraryId,
                    ref.seriesId,
                    ref.volumeId,
                    ref.chapterId,
                    if (unread) 0 else page,
                    anchor,
                    Instant.ofEpochMilli(readAt).toString(),
                )
            val state =
                KavitaReadingState(
                    ref,
                    progress,
                    total,
                    if (explicit) {
                        baselines[ref.chapterId] ?: previous?.baseline
                    } else if (old?.pending == true) {
                        previous?.baseline
                    } else {
                        baselines[ref.chapterId] ?: previous?.progress
                    },
                    explicit,
                    previous?.conflict == true && !explicit,
                )
            repository.putOperation(
                connectionId,
                account,
                KavitaOperation(
                    "progress/${ref.chapterId}",
                    json.encodeToString(state),
                    (old?.revision ?: 0) + 1,
                    true,
                ),
            )
            apply(state)
        }
        retryPending()
    }

    suspend fun pull(ref: KavitaChapterRef, total: Int): KavitaReadingState = flushMutex.withLock {
        val remote = api.progress(
            ref.chapterId,
        ).copy(libraryId = ref.libraryId, seriesId = ref.seriesId, volumeId = ref.volumeId, chapterId = ref.chapterId)
        mutex.withLock {
            checkSession()
            baselines[ref.chapterId] = remote
            val operation = operation(ref.chapterId)
            val old = operation?.let { json.decodeFromString<KavitaReadingState>(it.payload) }
            val snapshot = KavitaReadingState(ref, remote, total)
            if (operation?.pending == true) {
                if (old != null && kavitaProgressConflict(old, remote)) {
                    repository.putOperation(
                        connectionId,
                        account,
                        operation.copy(payload = json.encodeToString(old.copy(conflict = true))),
                    )
                }
            } else {
                repository.putOperation(
                    connectionId,
                    account,
                    KavitaOperation(
                        "progress/${ref.chapterId}",
                        json.encodeToString(snapshot),
                        (operation?.revision ?: 0) + 1,
                        false,
                    ),
                )
            }
            snapshot
        }
    }

    suspend fun accept(ref: KavitaChapterRef, page: Int, total: Int, readAt: Long) {
        mutex.withLock {
            checkSession()
            val old = operation(ref.chapterId)
            val progress =
                baselines[ref.chapterId]
                    ?: KavitaProgress(
                        ref.libraryId,
                        ref.seriesId,
                        ref.volumeId,
                        ref.chapterId,
                        page,
                        lastModifiedUtc = Instant.ofEpochMilli(readAt.coerceAtLeast(0)).toString(),
                    )
            val state = KavitaReadingState(ref, progress, total)
            repository.putOperation(
                connectionId,
                account,
                KavitaOperation(
                    "progress/${ref.chapterId}",
                    json.encodeToString(state),
                    (old?.revision ?: 0) + 1,
                    false,
                ),
            )
            apply(state)
        }
    }

    @Synchronized fun retryPending() {
        if (ConnectionRestoreState.isRestoring || restoring || !scope.isActive) return
        scheduled.set(true)
        if (job?.isActive == true) return
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                while (scheduled.getAndSet(false)) {
                    delay(750)
                    var wait = 2_000L
                    for (attempt in 0..5) {
                        try {
                            flush()
                            break
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (error is KavitaException &&
                                error.reason in
                                setOf(
                                    KavitaException.Reason.AUTHENTICATION,
                                    KavitaException.Reason.PERMISSION,
                                    KavitaException.Reason.ACCOUNT_CHANGED,
                                )
                            ) {
                                return@launch
                            }
                            if (attempt == 5) return@launch
                            delay(wait)
                            wait = (wait * 2).coerceAtMost(60_000)
                        }
                    }
                }
            } finally {
                synchronized(this@KavitaReadingCoordinator) {
                    job = null
                    if (scheduled.get() && scope.isActive) retryPending()
                }
            }
        }
        job?.start()
    }

    suspend fun flush() = flushMutex.withLock {
        if (ConnectionRestoreState.isRestoring || restoring) return@withLock
        for (operation in repository.operations(connectionId, account).filter {
            it.pending &&
                it.key.startsWith("progress/")
        }) {
            checkSession()
            val state = json.decodeFromString<KavitaReadingState>(operation.payload)
            if (state.conflict) continue
            val remote = api.progress(state.ref.chapterId)
            checkSession()
            if (sameKavitaPosition(remote, state.progress)) {
                confirmUploaded(operation, state)
                continue
            }
            if (kavitaProgressConflict(state, remote)) {
                mutex.withLock {
                    if (operation(state.ref.chapterId)?.revision == operation.revision) {
                        repository.putOperation(
                            connectionId,
                            account,
                            operation.copy(payload = json.encodeToString(state.copy(conflict = true))),
                        )
                    }
                }
                continue
            }
            if (ConnectionRestoreState.isRestoring || restoring) return@withLock
            api.saveProgress(state.progress)
            checkSession()
            confirmUploaded(operation, state)
        }
    }

    private suspend fun confirmUploaded(sent: KavitaOperation, uploaded: KavitaReadingState) = mutex.withLock {
        checkSession()
        repository.acknowledge(connectionId, account, sent.key, sent.revision)
        val current = operation(uploaded.ref.chapterId)
        if (current != null && current.pending && current.revision > sent.revision) {
            val state = json.decodeFromString<KavitaReadingState>(current.payload)
            // Only advance the baseline inherited from this upload; preserve independent conflict decisions.
            if (!state.conflict && state.baseline == uploaded.baseline) {
                repository.putOperation(
                    connectionId,
                    account,
                    current.copy(payload = json.encodeToString(state.copy(baseline = uploaded.progress))),
                )
            }
        }
        baselines[uploaded.ref.chapterId] = uploaded.progress
    }

    suspend fun prepareRestore() {
        restoring = true
        job?.cancel()
        flushMutex.withLock {
            mutex.withLock {
                repository.operations(connectionId, account).filter { it.key.startsWith("progress/") }.forEach {
                    repository.acknowledge(connectionId, account, it.key, it.revision)
                }
                baselines.clear()
            }
        }
        restoring = false
    }
}

internal fun sameKavitaPosition(a: KavitaProgress, b: KavitaProgress) =
    a.pageNum == b.pageNum && a.bookScrollId.orEmpty() == b.bookScrollId.orEmpty()

internal fun kavitaProgressConflict(local: KavitaReadingState, remote: KavitaProgress): Boolean {
    if (sameKavitaPosition(local.progress, remote)) return false
    if (local.baseline != null && sameKavitaPosition(local.baseline, remote)) return false
    if (local.explicit &&
        kavitaTimestamp(remote.lastModifiedUtc) <= kavitaTimestamp(local.progress.lastModifiedUtc)
    ) {
        return false
    }
    return remote.pageNum > 0 || !remote.bookScrollId.isNullOrEmpty() ||
        kavitaTimestamp(remote.lastModifiedUtc) > kavitaTimestamp(local.progress.lastModifiedUtc)
}
