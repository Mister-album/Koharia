package koharia.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Network work never holds the lock used to persist reading. Recheck revisions before committing results. */
internal suspend fun synchronizeLanraragiProgress(
    connectionId: Long,
    repository: LanraragiRepository,
    fetch: suspend (String) -> LanraragiEntry,
    push: suspend (String, Int) -> Unit,
    clearNew: suspend (String) -> Unit,
    apply: suspend (LanraragiReadState) -> Unit,
    stateMutex: Mutex = Mutex(),
    isCurrent: () -> Boolean = { true },
) {
    val pending = stateMutex.withLock { repository.pendingReadStates(connectionId) }
    for (local in pending) {
        if (local.requiresConfirmation) continue
        suspend fun unchanged(): Boolean = stateMutex.withLock {
            isCurrent() && repository.readState(connectionId, local.archiveId) == local
        }
        if (!unchanged()) continue
        val remote = fetch(local.archiveId)
        if (remote.pageCount <= 0 || !unchanged()) continue
        val target = if (local.totalPages ==
            0
        ) {
            remote.pageCount
        } else {
            (local.pageIndex + 1).coerceAtMost(remote.pageCount)
        }
        val resolved = if (remote.progress == target || shouldKeepLanraragiReading(local, remote)) {
            if (remote.progress != target) push(local.archiveId, target)
            if (!unchanged()) continue
            if (remote.isNew) clearNew(local.archiveId)
            local.copy(pageIndex = target - 1, totalPages = remote.pageCount, pending = false, initialPage = false)
        } else if (local.initialPage) {
            remote.toReadState()
        } else {
            // A newer, different remote position must not erase unconfirmed local reading.
            // The reader presents this conflict; background retries leave it pending.
            continue
        }
        stateMutex.withLock {
            if (isCurrent() &&
                repository.readState(connectionId, local.archiveId) == local
            ) {
                repository.record(connectionId, resolved)
                apply(resolved)
            }
        }
    }
}

internal fun shouldKeepLanraragiReading(local: LanraragiReadState, remote: LanraragiEntry): Boolean =
    if (local.initialPage) remote.progress == 0 else !local.localUnread && local.readAt >= remote.lastRead

internal fun hasLanraragiProgressConflict(local: LanraragiReadState?, remote: LanraragiEntry): Boolean =
    local != null && local.pending && !local.localUnread && !local.initialPage &&
        remote.lastRead > local.readAt && remote.progress > 0 && remote.progress != local.pageIndex + 1

internal fun shouldSkipInitialLanraragiReading(previous: LanraragiReadState?): Boolean =
    previous != null && !previous.localUnread && previous.pageIndex >= 0

internal fun LanraragiEntry.toReadState() = LanraragiReadState(
    id,
    (progress - 1).coerceAtLeast(-1),
    pageCount,
    lastRead,
    pending = false,
)

internal fun acceptsLocalReading(previous: LanraragiReadState?, observedAt: Long): Boolean =
    previous == null || (!previous.pending && !previous.localUnread) || observedAt >= previous.readAt
