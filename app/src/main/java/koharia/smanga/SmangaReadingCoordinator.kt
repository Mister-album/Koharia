package koharia.smanga

import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionRestoreState
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Reading state belongs to this immutable connection/account session, independently of shelf caches. */
class SmangaReadingCoordinator(
    private val connectionId: Long,
    private val accountKey: String,
    private val api: SmangaApi,
    private val repository: SmangaRepository,
    private val scope: CoroutineScope,
    private val checkSession: () -> Unit,
    private val applyState: suspend (SmangaReadState) -> Unit,
) {
    private val stateMutex = Mutex()
    private val epoch = AtomicLong()
    private val historySessions = ConcurrentHashMap<Long, String>()
    private val recordedHistorySessions = mutableMapOf<Long, String>()
    private val localConfirmations = ConcurrentHashMap<Long, LocalConfirmation>()
    private val offeredStates = mutableMapOf<Long, SmangaReadState>()
    private val lastPulledStates = mutableMapOf<Long, SmangaReadState>()
    private val lastUploadedStates = mutableMapOf<Long, SmangaReadState>()
    private val remoteBaselineRevisions = mutableMapOf<Long, Long>()
    private var lastRevision = 0L
    private var flushJob: Job? = null
    private var flushRequested = false

    /** The reader calls this once per chapter in a new reader session, never for adjacent page flips. */
    fun beginSession(chapterId: Long) {
        if (!scope.isActive || ConnectionRestoreState.isRestoring) return
        checkSession()
        historySessions[chapterId] = UUID.randomUUID().toString()
        localConfirmations.remove(chapterId)
    }

    suspend fun requireMappingConfirmation(chapterId: Long) {
        val operationEpoch = epoch.get()
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            pauseUploads(chapterId)
        }
    }

    suspend fun confirmLocal(chapter: SmangaChapter, pageIndex: Int, totalPages: Int, readAt: Long) {
        if (pageIndex < 0 || totalPages <= 0 || pageIndex >= totalPages) return
        val operationEpoch = epoch.get()
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            val selected = SmangaReadState(
                chapter.id,
                chapter.mangaId,
                pageIndex,
                totalPages,
                pageIndex == totalPages - 1,
                readAt.coerceAtLeast(0),
                nextRevision(state(chapter.id)),
                pending = true,
            )
            save(selected, operationEpoch)
            if (!isCurrent(operationEpoch)) return
            authorizeSelection(selected, offeredStates.remove(chapter.id) ?: lastPulledStates[chapter.id])
            recordHistory(chapter, readAt)
        }
        retryPending()
    }

    suspend fun record(
        chapter: SmangaChapter,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        if (pageIndex < 0 || totalPages <= 0 || pageIndex >= totalPages) return
        val operationEpoch = epoch.get()
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            val previous = state(chapter.id)
            if (previous != null && readAt < previous.readAt) return
            if (localConfirmations[chapter.id]?.totalPages?.let { it != totalPages } == true) {
                pauseUploads(chapter.id)
            }
            if (!initialPage || previous == null || previous.explicitUnread || previous.pageIndex < 0) {
                val next = SmangaReadState(
                    chapter.id, chapter.mangaId, pageIndex, totalPages, pageIndex == totalPages - 1,
                    readAt.coerceAtLeast(0), nextRevision(previous), pending = true,
                    initialPage = initialPage && previous?.explicitUnread != true,
                )
                save(next, operationEpoch)
                localConfirmations.computeIfPresent(chapter.id) { _, confirmation ->
                    confirmation.takeIf {
                        it.revision == previous?.revision && it.totalPages == totalPages &&
                            it.sessionId == historySessions[chapter.id]
                    }?.copy(revision = next.revision)
                }
            }
            if (!isCurrent(operationEpoch)) return
            recordHistory(chapter, readAt)
        }
        retryPending()
    }

    suspend fun pull(chapter: SmangaChapter, chapterMemo: JsonObject): ConnectionPageProgressSnapshot? {
        val operationEpoch = epoch.get()
        val (before, baselineRevision) = stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return null
            state(chapter.id) to remoteBaselineRevisions[chapter.id]
        }
        val remote = api.chapters(chapter.mangaId).firstOrNull { it.id == chapter.id } ?: return null
        return stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return null
            val current = state(chapter.id)
            val physicalCount = ConnectionChapterMetadata.pagesCount(chapterMemo) ?: chapter.pageCount
            if (baselineRevision != remoteBaselineRevisions[chapter.id]) {
                val confirmation = uploadPaused(chapter.id)
                val selected = if (confirmation) {
                    offeredStates[chapter.id] ?: lastPulledStates[chapter.id] ?: current
                } else {
                    current ?: lastPulledStates[chapter.id]
                } ?: return null
                if (!isCurrent(operationEpoch)) return null
                return snapshot(
                    selected,
                    chapterMemo,
                    confirmation,
                    physicalCount > 0 && selected.totalPages > 0 && physicalCount != selected.totalPages,
                )
            }
            val remoteState = remote.readState()
            lastPulledStates[chapter.id] = remoteState
            advanceRemoteBaseline(chapter.id)
            val countChanged = physicalCount > 0 && remoteState.totalPages > 0 &&
                physicalCount != remoteState.totalPages
            val localConfirmation = current?.let(::confirmationFor)
            if (localConfirmation != null && localConfirmation.remote == null) {
                localConfirmations.replace(
                    chapter.id,
                    localConfirmation,
                    localConfirmation.copy(remote = remoteState.fingerprint()),
                )
            }
            if (localConfirmation != null && !localConfirmation.matches(remoteState)) {
                pauseUploads(chapter.id)
            }
            val explicitlySelected = localConfirmation?.matches(remoteState) == true
            if (!explicitlySelected && (current == before || current?.pending == true) &&
                (countChanged || (current?.pending == true && conflicts(current, remoteState)))
            ) {
                pauseUploads(chapter.id)
            }
            val confirmation = uploadPaused(chapter.id)
            val selected = when {
                current != before -> current ?: return null
                confirmation -> remoteState.also { offeredStates[chapter.id] = it }
                current?.pending == true && samePosition(current, remoteState) -> {
                    acknowledge(current, operationEpoch)
                    current.copy(pending = false)
                }
                current?.pending == true -> current
                remote.latest == null && current != null && !explicitlySelected -> {
                    offeredStates[chapter.id] = remoteState
                    resetAcknowledgedState(current, remoteState, operationEpoch)
                }
                current != null && current.readAt >= remoteState.readAt -> current
                else -> remoteState.also { offeredStates[chapter.id] = it }
            }
            if (!isCurrent(operationEpoch)) return null
            snapshot(selected, chapterMemo, confirmation, countChanged)
        }
    }

    suspend fun accept(chapter: SmangaChapter, pageIndex: Int, totalPages: Int, readAt: Long) {
        if (pageIndex < 0 || totalPages < 0 || (totalPages > 0 && pageIndex >= totalPages)) return
        val operationEpoch = epoch.get()
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            val remote = offeredStates.remove(chapter.id) ?: lastPulledStates[chapter.id]
            val offered = remote?.takeIf {
                it.readAt == readAt && it.pageIndex.coerceAtLeast(0) == pageIndex
            }
            val accepted = SmangaReadState(
                chapter.id, chapter.mangaId, pageIndex, totalPages,
                offered?.completed ?: (totalPages > 0 && pageIndex == totalPages - 1),
                readAt.coerceAtLeast(0), nextRevision(state(chapter.id)), pending = false,
                explicitUnread = offered?.explicitUnread == true,
            )
            save(accepted, operationEpoch)
            if (!isCurrent(operationEpoch)) return
            authorizeSelection(accepted, remote)
        }
    }

    suspend fun setRead(chapter: SmangaChapter, read: Boolean) {
        val operationEpoch = epoch.get()
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            val previous = state(chapter.id)
            val count = chapter.pageCount.takeIf { it > 0 } ?: previous?.totalPages ?: 0
            val next = SmangaReadState(
                chapter.id, chapter.mangaId, if (read) (count - 1).coerceAtLeast(0) else 0, count,
                completed = read, readAt = System.currentTimeMillis(), revision = nextRevision(previous),
                pending = true, explicitUnread = !read,
            )
            save(next, operationEpoch)
            if (!isCurrent(operationEpoch)) return
            authorizeSelection(next, offeredStates.remove(chapter.id) ?: lastPulledStates[chapter.id])
        }
        retryPending()
    }

    suspend fun syncManga(mangaId: Long) {
        val operationEpoch = epoch.get()
        val (before, baselineRevisions) = stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            repository.readStatesForManga(connectionId, accountKey, mangaId).associateBy { it.chapterId } to
                remoteBaselineRevisions.toMap()
        }
        val remote = api.chapters(mangaId)
        stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            for (chapter in remote) {
                if (!isCurrent(operationEpoch)) return
                val current = state(chapter.id)
                if (current != before[chapter.id] || current?.pending == true || uploadPaused(chapter.id)) continue
                if (baselineRevisions[chapter.id] != remoteBaselineRevisions[chapter.id]) continue
                val next = chapter.readState()
                if (chapter.latest == null) {
                    // The server marks a manga unread by deleting latest, without a new timestamp.
                    if (current == null) continue
                    lastPulledStates[chapter.id] = next
                    offeredStates.remove(chapter.id)
                    advanceRemoteBaseline(chapter.id)
                    resetAcknowledgedState(current, next, operationEpoch)
                    continue
                }
                if (current != null && next.readAt <= current.readAt) continue
                save(next.copy(revision = nextRevision(current)), operationEpoch)
            }
        }
    }

    suspend fun prepareRestore(chapterIds: List<Long>) {
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        checkSession()
        synchronized(this) {
            flushRequested = false
            flushJob?.cancel()
        }
        stateMutex.withLock {
            currentCoroutineContext().ensureActive()
            scope.coroutineContext.ensureActive()
            checkSession()
            epoch.incrementAndGet()
            offeredStates.clear()
            lastPulledStates.clear()
            lastUploadedStates.clear()
            remoteBaselineRevisions.clear()
            val ids = chapterIds.toSet()
            repository.resetReadStates(connectionId, accountKey, chapterIds)
            ids.forEach {
                setUploadPause(it, false)
                localConfirmations.remove(it)
            }
            repository.pendingHistoryEvents(connectionId, accountKey)
                .filter { it.chapterId in ids }
                .forEach { repository.updateHistoryStatus(connectionId, accountKey, it.id, HISTORY_ATTEMPTED) }
        }
    }

    @Synchronized
    fun retryPending() {
        if (!scope.isActive || ConnectionRestoreState.isRestoring) return
        try {
            checkSession()
        } catch (_: CancellationException) {
            return
        }
        flushRequested = true
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(start = CoroutineStart.LAZY) {
            var exhausted = false
            try {
                delay(1000)
                var failures = 0
                while (failures < MAX_ATTEMPTS) {
                    val requested = synchronized(this@SmangaReadingCoordinator) {
                        flushRequested.also { flushRequested = false }
                    }
                    if (!requested) break
                    val failed = try {
                        flush()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        true
                    }
                    if (failed) {
                        failures++
                        if (failures == MAX_ATTEMPTS) {
                            exhausted = true
                            break
                        }
                        delay(5000L * failures)
                        synchronized(this@SmangaReadingCoordinator) { flushRequested = true }
                    }
                }
            } finally {
                val job = currentCoroutineContext()[Job]
                synchronized(this@SmangaReadingCoordinator) {
                    if (flushJob === job) {
                        flushJob = null
                        if (exhausted) flushRequested = false
                        if (flushRequested) retryPending()
                    }
                }
            }
        }.also(Job::start)
    }

    /** The successful chapter read also avoids consuming pending history attempts while already offline. */
    private suspend fun flush(): Boolean {
        val operationEpoch = epoch.get()
        val (pending, history) = stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return false
            repository.pendingReadStates(connectionId, accountKey).filterNot { uploadPaused(it.chapterId) } to
                repository.pendingHistoryEvents(connectionId, accountKey)
        }
        var failed = false
        val mangaIds = (pending.map { it.mangaId } + history.map { it.mangaId }).distinct()
        for (mangaId in mangaIds) {
            if (!isCurrent(operationEpoch)) return false
            try {
                val remote = api.chapters(mangaId).associateBy { it.id }
                for (local in pending.filter { it.mangaId == mangaId }) {
                    val chapter = remote[local.chapterId] ?: continue
                    val target = chapter.readState()
                    if (!allowUpload(local, target, operationEpoch)) continue
                    val written = if (local.explicitUnread) {
                        api.markUnread(local.mangaId, local.chapterId, local.totalPages)
                    } else {
                        api.pushProgress(
                            local.mangaId,
                            local.chapterId,
                            local.pageIndex,
                            local.totalPages,
                            local.completed,
                        )
                    }
                    stateMutex.withLock {
                        if (!isCurrent(operationEpoch)) return false
                        val writtenState = chapter.copy(latest = written).readState()
                        lastUploadedStates[local.chapterId] = writtenState
                        advanceRemoteBaseline(local.chapterId)
                        if (!uploadPaused(local.chapterId)) {
                            // Advance our own remote baseline, including a selection made during this write.
                            lastPulledStates[local.chapterId] = writtenState
                            offeredStates.remove(local.chapterId)
                            localConfirmations.computeIfPresent(local.chapterId) { _, confirmation ->
                                if (confirmation.matches(target)) {
                                    confirmation.copy(remote = writtenState.fingerprint(), attempted = null)
                                } else {
                                    confirmation
                                }
                            }
                        }
                        acknowledge(local, operationEpoch)
                    }
                }
                for (event in history.filter { it.mangaId == mangaId && it.chapterId in remote }) {
                    sendHistory(event, operationEpoch)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true
            }
        }
        return failed
    }

    private suspend fun sendHistory(event: SmangaHistoryEvent, operationEpoch: Long) {
        val claimed = stateMutex.withLock {
            if (!isCurrent(operationEpoch)) return
            val current = repository.historyEvent(connectionId, accountKey, event.id)
            if (current?.status != HISTORY_PENDING) return
            repository.updateHistoryStatus(connectionId, accountKey, event.id, HISTORY_ATTEMPTED)
            true
        }
        if (!claimed || !isCurrent(operationEpoch)) return
        try {
            api.addHistory(event.mediaId, event.mangaId, event.chapterId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // An attempted non-idempotent request is never replayed after an uncertain result.
            return
        }
        stateMutex.withLock {
            if (isCurrent(operationEpoch)) {
                repository.updateHistoryStatus(connectionId, accountKey, event.id, HISTORY_SENT)
            }
        }
    }

    private suspend fun allowUpload(
        local: SmangaReadState,
        remote: SmangaReadState,
        operationEpoch: Long,
    ): Boolean = stateMutex.withLock {
        if (!isCurrent(operationEpoch) || state(local.chapterId) != local || uploadPaused(local.chapterId)) {
            return@withLock false
        }
        if (samePosition(local, remote)) {
            acknowledge(local, operationEpoch)
            return@withLock false
        }
        val confirmation = confirmationFor(local)
        if ((confirmation != null && !confirmation.matches(remote)) ||
            (confirmation == null && conflicts(local, remote))
        ) {
            pauseUploads(local.chapterId)
            return@withLock false
        }
        if (confirmation != null) {
            // A concurrent page flip may prevent CAS. Recognize this write when the next fetch observes it.
            localConfirmations.replace(
                local.chapterId,
                confirmation,
                confirmation.copy(remote = remote.fingerprint(), attempted = local.fingerprint()),
            )
            if (confirmationFor(local) == null) return@withLock false
        }
        true
    }

    private fun confirmationFor(local: SmangaReadState): LocalConfirmation? = localConfirmations[local.chapterId]
        ?.takeIf {
            it.revision == local.revision && it.totalPages == local.totalPages &&
                it.sessionId == historySessions[local.chapterId]
        }

    private suspend fun authorizeSelection(local: SmangaReadState, remote: SmangaReadState?) {
        setUploadPause(local.chapterId, false)
        localConfirmations[local.chapterId] = LocalConfirmation(
            sessionId = historySessions.getOrPut(local.chapterId) { UUID.randomUUID().toString() },
            revision = local.revision,
            totalPages = local.totalPages,
            remote = remote?.fingerprint(),
        )
    }

    private fun SmangaReadState.fingerprint() = RemoteFingerprint(
        readAt,
        if (explicitUnread) -1 else pageIndex,
        totalPages,
        completed,
        explicitUnread,
    )

    private data class LocalConfirmation(
        val sessionId: String,
        val revision: Long,
        val totalPages: Int,
        val remote: RemoteFingerprint?,
        val attempted: RemoteFingerprint? = null,
    ) {
        fun matches(state: SmangaReadState): Boolean = remote == null || remote == RemoteFingerprint(
            state.readAt,
            state.pageIndex,
            state.totalPages,
            state.completed,
            state.explicitUnread,
        ) || attempted?.let {
            it.pageIndex == state.pageIndex && it.totalPages == state.totalPages &&
                it.completed == state.completed && it.explicitUnread == state.explicitUnread
        } == true
    }

    private data class RemoteFingerprint(
        val readAt: Long,
        val pageIndex: Int,
        val totalPages: Int,
        val completed: Boolean,
        val explicitUnread: Boolean,
    )

    private suspend fun acknowledge(local: SmangaReadState, operationEpoch: Long) {
        if (isCurrent(operationEpoch) && state(local.chapterId) == local && !uploadPaused(local.chapterId) &&
            repository.acknowledgeReadState(connectionId, accountKey, local.chapterId, local.revision)
        ) {
            confirmationFor(local)?.let { localConfirmations.remove(local.chapterId, it) }
            if (isCurrent(operationEpoch)) applyState(local.copy(pending = false))
        }
    }

    private suspend fun recordHistory(chapter: SmangaChapter, readAt: Long) {
        val historySession = historySessions.getOrPut(chapter.id) { UUID.randomUUID().toString() }
        if (recordedHistorySessions[chapter.id] == historySession) return
        repository.enqueueHistory(
            connectionId,
            accountKey,
            SmangaHistoryEvent(historySession, chapter.mangaId, chapter.id, chapter.mediaId, readAt.coerceAtLeast(0)),
        )
        recordedHistorySessions[chapter.id] = historySession
    }

    private suspend fun uploadPaused(chapterId: Long): Boolean {
        val entry = repository.cache(connectionId, accountKey, CONFIRMATION_GROUP, chapterId.toString()) ?: return false
        return try {
            Json.decodeFromString<SmangaUploadConfirmation>(entry.payload).required
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    private suspend fun pauseUploads(chapterId: Long) {
        setUploadPause(chapterId, true)
        localConfirmations.remove(chapterId)
    }

    private suspend fun setUploadPause(chapterId: Long, required: Boolean) {
        if (uploadPaused(chapterId) == required) return
        repository.putCache(
            connectionId,
            accountKey,
            CONFIRMATION_GROUP,
            SmangaCacheEntry(
                chapterId.toString(),
                Json.encodeToString(SmangaUploadConfirmation(required)),
                System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun state(chapterId: Long) = repository.readState(connectionId, accountKey, chapterId)

    private fun advanceRemoteBaseline(chapterId: Long) {
        remoteBaselineRevisions[chapterId] = (remoteBaselineRevisions[chapterId] ?: 0) + 1
    }

    private suspend fun save(state: SmangaReadState, operationEpoch: Long) {
        if (!isCurrent(operationEpoch)) return
        repository.putReadState(connectionId, accountKey, state)
        if (isCurrent(operationEpoch)) applyState(state)
    }

    private suspend fun resetAcknowledgedState(
        local: SmangaReadState,
        remote: SmangaReadState,
        operationEpoch: Long,
    ): SmangaReadState {
        localConfirmations.remove(local.chapterId)
        lastUploadedStates.remove(local.chapterId)
        if (local == remote.copy(revision = local.revision)) return local
        return remote.copy(revision = nextRevision(local)).also { save(it, operationEpoch) }
    }

    private fun nextRevision(previous: SmangaReadState?): Long {
        lastRevision = maxOf(lastRevision, previous?.revision ?: 0) + 1
        return lastRevision
    }

    private suspend fun isCurrent(operationEpoch: Long): Boolean {
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        checkSession()
        return !ConnectionRestoreState.isRestoring && operationEpoch == epoch.get()
    }

    private fun SmangaChapter.readState(): SmangaReadState {
        val progress = latest
        val count = progress?.totalPages?.takeIf { it > 0 } ?: pageCount.coerceAtLeast(0)
        val pageIndex = when {
            progress == null -> -1
            progress.completed && progress.totalPages <= 0 -> (count - 1).coerceAtLeast(-1)
            else -> progress.pageIndex
        }
        return SmangaReadState(
            id, mangaId, pageIndex, count, progress?.completed == true,
            progress?.updatedAt ?: 0, revision = 0, pending = false,
            explicitUnread = progress == null || (progress.pageIndex < 0 && !progress.completed),
        )
    }

    private fun samePosition(local: SmangaReadState, remote: SmangaReadState): Boolean = when {
        local.explicitUnread -> remote.pageIndex < 0 && !remote.completed
        pageCountChanged(local, remote) -> false
        local.completed && remote.completed -> true
        else -> local.pageIndex == remote.pageIndex && local.completed == remote.completed
    }

    private fun conflicts(local: SmangaReadState, remote: SmangaReadState): Boolean {
        val ownUpload = lastUploadedStates[local.chapterId]?.fingerprint() == remote.fingerprint()
        return !samePosition(local, remote) &&
            (
                pageCountChanged(local, remote) || (remote.readAt > local.readAt && !ownUpload) ||
                    (local.initialPage && (remote.pageIndex >= 0 || remote.completed))
                )
    }

    private fun pageCountChanged(local: SmangaReadState, remote: SmangaReadState): Boolean =
        local.totalPages > 0 && remote.totalPages > 0 && local.totalPages != remote.totalPages

    private fun snapshot(
        state: SmangaReadState,
        memo: JsonObject,
        confirmation: Boolean,
        countChanged: Boolean,
    ): ConnectionPageProgressSnapshot {
        val version = (memo["smangaManifestVersion"] as? JsonPrimitive)?.contentOrNull
        return ConnectionPageProgressSnapshot(
            resourceId = state.chapterId.toString(),
            pageIndex = state.pageIndex.takeIf {
                it >= 0 && !state.explicitUnread && !(state.completed && state.totalPages == 0)
            },
            totalPages = state.totalPages, completed = state.completed,
            readDate = Instant.ofEpochMilli(state.readAt).toString(), isEpub = false, canOpenAsPages = false,
            updatedChapterMemo = if (countChanged) {
                memo
            } else {
                ConnectionChapterMetadata.withPagesCount(
                    memo,
                    state.totalPages,
                )
            },
            previousPublicationVersion = version, publicationVersion = version, requiresConfirmation = confirmation,
            requiresPageMappingConfirmation = confirmation,
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val HISTORY_PENDING = 0
        const val HISTORY_ATTEMPTED = 1
        const val HISTORY_SENT = 2
        const val CONFIRMATION_GROUP = "reading-confirmation"
    }
}

@Serializable
private data class SmangaUploadConfirmation(val required: Boolean)
