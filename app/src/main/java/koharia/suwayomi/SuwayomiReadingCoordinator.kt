package koharia.suwayomi

import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionRestoreState
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SuwayomiRemoteState(val page: Int, val count: Int, val read: Boolean, val readAt: Long) {
    companion object {
        fun from(chapter: SuwayomiChapter) = SuwayomiRemoteState(
            chapter.lastPageRead,
            chapter.pageCount,
            chapter.isRead,
            chapter.lastReadAt,
        )
    }
}

@Serializable
data class SuwayomiReadState(
    val mangaId: Int,
    val chapterId: Int,
    val page: Int,
    val count: Int,
    val read: Boolean,
    val readAt: Long,
    val baseline: SuwayomiRemoteState? = null,
    val conflict: Boolean = false,
    val initial: Boolean = false,
    val explicitRead: Boolean? = null,
)

/** Local revisions and remote baselines survive process death; server timestamps are not local revisions. */
class SuwayomiReadingCoordinator(
    private val identity: SuwayomiIdentity,
    private val repository: SuwayomiRepository,
    private val api: SuwayomiService,
    private val json: Json,
    private val scope: CoroutineScope,
    private val checkSession: () -> Unit,
    private val applyState: suspend (SuwayomiReadState) -> Unit,
) {
    private val mutex = Mutex()
    private val remoteMutex = Mutex()
    private val offered = ConcurrentHashMap<Int, SuwayomiRemoteState>()
    private var flushJob: Job? = null
    private var flushRequested = false
    private var lastRevision = 0L

    suspend fun operation(chapterId: Int): SuwayomiOperation? =
        repository.operations(identity.connectionId, identity.account).firstOrNull { it.key == chapterId.toString() }
    private fun SuwayomiOperation.state() = json.decodeFromString<SuwayomiReadState>(payload)

    /**
     * Read state held locally for a series, including operations that could not be uploaded yet.
     * Migration must carry this too, otherwise offline reading would be lost with the old entry.
     */
    suspend fun localChapterStates(mangaId: Int): Map<Int, SuwayomiReadState> {
        checkSession()
        return repository.operations(identity.connectionId, identity.account)
            .mapNotNull { operation ->
                runCatching { json.decodeFromString<SuwayomiReadState>(operation.payload) }.getOrNull()
            }
            .filter { it.mangaId == mangaId }
            .associateBy { it.chapterId }
    }
    private fun available(): Boolean = scope.isActive && !ConnectionRestoreState.isRestoring
    private fun revision(previous: SuwayomiOperation?) =
        maxOf(System.currentTimeMillis(), lastRevision + 1, (previous?.revision ?: 0) + 1).also { lastRevision = it }

    private suspend fun save(
        state: SuwayomiReadState,
        previous: SuwayomiOperation?,
        pending: Boolean,
    ): SuwayomiOperation {
        checkSession()
        return SuwayomiOperation(
            state.chapterId.toString(),
            json.encodeToString(state),
            revision(previous),
            pending,
        ).also {
            repository.putOperation(identity.connectionId, identity.account, it)
        }
    }

    suspend fun record(mangaId: Int, chapterId: Int, page: Int, count: Int, readAt: Long, initial: Boolean) {
        if (!available() || page !in 0 until count) return
        mutex.withLock {
            checkSession()
            val previous = operation(chapterId)
            val old = previous?.state()
            if (initial && old != null && old.explicitRead != false) return@withLock
            val completed = old?.read == true || page == count - 1
            save(
                SuwayomiReadState(
                    mangaId, chapterId, page, count, completed, readAt, old?.baseline,
                    old?.conflict == true || (old != null && old.count > 0 && old.count != count),
                    initial = initial && old?.explicitRead != false,
                    explicitRead = old?.explicitRead?.takeUnless { !it && completed },
                ),
                previous,
                true,
            )
        }
        retryPending()
    }

    suspend fun pull(
        mangaId: Int,
        chapterId: Int,
        memo: JsonObject,
    ): ConnectionPageProgressSnapshot = remoteMutex.withLock {
        checkSession()
        val chapter = api.chapter(chapterId)
        require(chapter.mangaId == mangaId)
        val remote = SuwayomiRemoteState.from(chapter)
        mutex.withLock {
            checkSession()
            val previous = operation(chapterId)
            val old = previous?.state()
            val physicalCount = ConnectionChapterMetadata.pagesCount(memo) ?: remote.count
            val mappingChanged = physicalCount > 0 && remote.count > 0 && physicalCount != remote.count
            val conflict = old?.conflict == true || mappingChanged ||
                (
                    previous?.pending == true && old != null && !old.initial && old.baseline != remote &&
                        !matches(old, remote)
                    )
            offered[chapterId] = remote
            val state = when {
                conflict && old != null -> {
                    save(old.copy(conflict = true), previous, previous.pending)
                    old
                }
                previous?.pending == true && old != null && !old.initial && !matches(old, remote) -> old
                else -> SuwayomiReadState(
                    mangaId,
                    chapterId,
                    remote.page,
                    remote.count,
                    remote.read,
                    remote.readAt * 1000,
                    remote,
                ).also { save(it, previous, false) }
            }
            val selected = if (conflict) {
                remote
            } else {
                SuwayomiRemoteState(state.page, state.count, state.read, remote.readAt)
            }
            snapshot(chapterId, selected, memo, conflict, mappingChanged)
        }
    }

    suspend fun readerReady(mangaId: Int, chapterId: Int, page: Int, count: Int) {
        if (!available() || page !in 0 until count) return
        mutex.withLock {
            val previous = operation(chapterId) ?: return@withLock
            val old = previous.state()
            // Ordinary reader pushes cannot approve a conflict discovered by the background queue.
            if (old.conflict) return@withLock
            if (old.initial) {
                save(
                    old.copy(
                        mangaId = mangaId,
                        page = page,
                        count = count,
                        read = old.read || page == count - 1,
                        baseline = offered.remove(chapterId) ?: old.baseline,
                        initial = false,
                    ),
                    previous,
                    true,
                )
            }
        }
        retryPending()
    }

    private fun matches(state: SuwayomiReadState, remote: SuwayomiRemoteState): Boolean =
        state.page == remote.page && (state.count == remote.count || state.count == 0) &&
            (state.explicitRead ?: state.read) == remote.read

    private fun snapshot(id: Int, state: SuwayomiRemoteState, memo: JsonObject, conflict: Boolean, mapping: Boolean) =
        ConnectionPageProgressSnapshot(
            resourceId = id.toString(), pageIndex = state.page, totalPages = state.count,
            completed = state.read,
            readDate = state.readAt.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() },
            isEpub = false, canOpenAsPages = true,
            updatedChapterMemo = ConnectionChapterMetadata.withPagesCount(memo, state.count),
            previousPublicationVersion = null, publicationVersion = null,
            requiresConfirmation = conflict, requiresPageMappingConfirmation = mapping,
            blocksAutomaticSelection = conflict,
        )

    suspend fun confirm(mangaId: Int, chapterId: Int, page: Int, count: Int, readAt: Long) {
        if (!available() || page !in 0 until count) return
        mutex.withLock {
            val previous = operation(chapterId)
            val old = previous?.state()
            save(
                SuwayomiReadState(
                    mangaId,
                    chapterId,
                    page,
                    count,
                    old?.read == true || page == count - 1,
                    readAt,
                    offered.remove(chapterId) ?: old?.baseline,
                    explicitRead = old?.explicitRead?.takeUnless { !it && page == count - 1 },
                ),
                previous,
                true,
            )
        }
        retryPending()
    }

    suspend fun accept(mangaId: Int, chapterId: Int, page: Int, count: Int, readAt: Long) {
        if (!available()) return
        mutex.withLock {
            val previous = operation(chapterId)
            val remote = offered.remove(chapterId) ?: previous?.state()?.baseline ?: return@withLock
            val state = SuwayomiReadState(mangaId, chapterId, page, count, remote.read, readAt, remote)
            save(state, previous, false)
            applyState(state)
        }
    }

    suspend fun markRead(mangaId: Int, chapterId: Int, read: Boolean) {
        if (!available()) return
        mutex.withLock {
            val previous = operation(chapterId)
            val old = previous?.state()
            val count = old?.count ?: 0
            val state = SuwayomiReadState(
                mangaId,
                chapterId,
                if (read && count > 0) count - 1 else 0,
                count,
                read,
                old?.readAt ?: 0,
                old?.baseline,
                explicitRead = read,
            )
            save(state, previous, true)
            applyState(state)
        }
        retryPending()
    }

    suspend fun pause(chapterId: Int) = mutex.withLock {
        operation(chapterId)?.let { save(it.state().copy(conflict = true), it, it.pending) }
    }

    suspend fun prepareRestore(ids: List<Int>) = mutex.withLock {
        flushJob?.cancel()
        for (id in ids) operation(id)?.let { save(it.state().copy(conflict = true), it, it.pending) }
    }

    suspend fun syncManga(mangaId: Int): Unit = remoteMutex.withLock {
        if (!available()) return
        val remote = api.chapters(mangaId)
        for (chapter in remote) {
            mutex.withLock {
                if (!available()) return
                checkSession()
                val previous = operation(chapter.id)
                val old = previous?.state()
                val baseline = SuwayomiRemoteState.from(chapter)
                if (previous?.pending == true || old?.conflict == true) return@withLock
                val state = SuwayomiReadState(
                    mangaId,
                    chapter.id,
                    chapter.lastPageRead,
                    chapter.pageCount,
                    chapter.isRead,
                    chapter.lastReadAt * 1000,
                    baseline,
                )
                save(state, previous, false)
                applyState(state)
            }
        }
    }

    @Synchronized
    fun retryPending() {
        if (!available()) return
        if (flushJob?.isActive == true) {
            flushRequested = true
            return
        }
        flushRequested = false
        flushJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(750)
                var backoff = 2000L
                while (available()) {
                    try {
                        flush()
                        val retry = repository.operations(identity.connectionId, identity.account)
                            .any { it.pending && !it.state().conflict && !it.state().initial }
                        if (!retry) return@launch
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            } finally {
                synchronized(this@SuwayomiReadingCoordinator) {
                    flushJob = null
                    if (flushRequested) retryPending()
                }
            }
        }
        flushJob?.start()
    }

    internal suspend fun flush(): Unit = remoteMutex.withLock {
        checkSession()
        if (!available()) return
        val pending = repository.operations(identity.connectionId, identity.account).filter { it.pending }
        var failure: Exception? = null
        for (operation in pending) {
            try {
                flushOperation(operation)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private suspend fun flushOperation(operation: SuwayomiOperation) {
        val state = operation.state()
        if (state.conflict || state.initial) return
        val remoteChapter = api.chapter(state.chapterId)
        require(remoteChapter.mangaId == state.mangaId)
        val remote = SuwayomiRemoteState.from(remoteChapter)
        val allowed = mutex.withLock {
            checkSession()
            if (!available() || this.operation(state.chapterId)?.revision != operation.revision) {
                return@withLock false
            }
            if (matches(state, remote)) {
                acknowledge(operation, state, remote)
                false
            } else if (state.explicitRead == null && (state.baseline == null || state.baseline != remote)) {
                save(state.copy(conflict = true), operation, true)
                offered[state.chapterId] = remote
                false
            } else {
                true
            }
        }
        if (!allowed) return
        val response = api.updateChapter(
            state.chapterId,
            if (state.explicitRead == null || state.count > 0 || state.explicitRead == false) state.page else null,
            state.explicitRead ?: true.takeIf { state.read },
        )
        mutex.withLock {
            checkSession()
            if (!available()) return
            val returned = SuwayomiRemoteState.from(response)
            if (response.mangaId != state.mangaId || (state.count > 0 && !matches(state, returned))) {
                this.operation(state.chapterId)?.let { save(it.state().copy(conflict = true), it, true) }
                offered[state.chapterId] = returned
            } else {
                acknowledge(operation, state, returned)
            }
        }
    }

    private suspend fun acknowledge(
        operation: SuwayomiOperation,
        state: SuwayomiReadState,
        remote: SuwayomiRemoteState,
    ) {
        val next = state.copy(baseline = remote, explicitRead = null, initial = false)
        repository.acknowledge(
            identity.connectionId,
            identity.account,
            operation.copy(payload = json.encodeToString(next), pending = false),
        )
        val current = this.operation(state.chapterId)
        if (current?.revision == operation.revision) {
            applyState(next)
        } else if (current?.pending == true) {
            // A page flip during the upload belongs to the same confirmed remote baseline.
            val latest = current.state()
            if (!latest.conflict && latest.baseline == state.baseline) {
                save(latest.copy(baseline = remote), current, true)
            }
        }
    }
}
