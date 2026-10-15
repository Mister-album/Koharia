package koharia.komga.progress

import eu.kanade.tachiyomi.network.HttpException
import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.coroutines.CancellationException

internal interface KomgaPageTransport {
    suspend fun read(): KomgaProgressBaseline
    suspend fun write(page: Int, completed: Boolean)
}

internal enum class KomgaProgressDispatchResult { IDLE, SYNCED, PENDING, AWAITING_CONFIRMATION, HELD, CONFLICT, DENIED }

/** Only stored generations authorize sends. A wakeup never supplies a new page payload. */
internal class KomgaPageDispatcher(
    private val repository: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
) {
    suspend fun dispatch(
        token: KomgaProgressRevision,
        transport: KomgaPageTransport,
        checkOwner: () -> Unit,
    ): KomgaProgressDispatchResult = ordering.remote(token, checkOwner, allowPageAttemptRecovery = true) {
        suspend fun checkCurrent() {
            checkOwner()
            ordering.checkCurrent(token)
        }
        var readFailure = KomgaProgressDispatchResult.DENIED
        suspend fun read(): KomgaProgressBaseline? = try {
            transport.read().also { checkCurrent() }
        } catch (error: HttpException) {
            val phase = error.progressFailurePhase() ?: throw error
            checkCurrent()
            repository.get(token.key)?.let {
                repository.transition(it, phase, checkOwner)
            }
            readFailure = phase.failureResult()
            null
        }
        val unresolved = repository.attempt(token.key)
        if (unresolved != null) {
            // An earlier generation may have reached the server even if its reader or worker died.
            val remote = read() ?: return@remote readFailure
            return@remote reconcile(unresolved, remote, checkOwner)
        }
        val event = repository.get(token.key) ?: return@remote KomgaProgressDispatchResult.IDLE
        if (event.supersededByRevision != null) return@remote KomgaProgressDispatchResult.IDLE
        if (event.revision != token.revision) return@remote KomgaProgressDispatchResult.HELD
        when (event.phase) {
            KomgaReadStatusPhase.SYNCED -> return@remote KomgaProgressDispatchResult.IDLE
            KomgaReadStatusPhase.CONFLICT -> return@remote KomgaProgressDispatchResult.CONFLICT
            KomgaReadStatusPhase.DENIED -> return@remote KomgaProgressDispatchResult.DENIED
            KomgaReadStatusPhase.HELD -> return@remote KomgaProgressDispatchResult.HELD
            KomgaReadStatusPhase.PENDING -> Unit
        }
        val baseline = KomgaProgressBaseline.decode(event.baseline)
        if (baseline == null || baseline.bookId != token.key.bookId || baseline.total.toLong() != event.total ||
            (baseline.epub && !baseline.divina)
        ) {
            repository.transition(event, KomgaReadStatusPhase.HELD, checkOwner)
            return@remote KomgaProgressDispatchResult.HELD
        }
        val remote = read() ?: return@remote readFailure
        if (remote != baseline) {
            repository.transition(event, KomgaReadStatusPhase.CONFLICT, checkOwner)
            return@remote KomgaProgressDispatchResult.CONFLICT
        }
        val attempt = repository.begin(event, remote.encode(), checkOwner)
            ?: return@remote KomgaProgressDispatchResult.PENDING
        try {
            checkCurrent()
            transport.write((event.page + 1).toInt(), event.page + 1 == event.total)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            checkCurrent()
            val phase = error.progressFailurePhase() ?: throw error
            if (phase == KomgaReadStatusPhase.DENIED) {
                repository.settle(attempt, phase, null, checkOwner)
            } else {
                // A missing resource does not prove that an already sent request had no effect.
                repository.get(token.key)?.let { repository.transition(it, phase, checkOwner) }
            }
            return@remote phase.failureResult()
        }
        checkCurrent()
        // A 2xx alone does not supply the server's new revision or prove its resulting state.
        val confirmed = read() ?: return@remote readFailure
        reconcile(attempt, confirmed, checkOwner)
    }

    /** Caller must hold the shared per-book ordering lock, including when resolving before an explicit intent. */
    internal suspend fun reconcile(
        attempt: KomgaPageAttempt,
        remote: KomgaProgressBaseline,
        checkOwner: () -> Unit,
    ): KomgaProgressDispatchResult {
        val before = KomgaProgressBaseline.decode(attempt.baseline)
        val event = attempt.event
        val result = when {
            before == null || before.bookId != event.key.bookId -> KomgaProgressDispatchResult.HELD
            remote.matchesPageTarget(before, event.page, event.total) -> {
                repository.settle(attempt, KomgaReadStatusPhase.SYNCED, remote.encode(), checkOwner)
                return when (repository.get(event.key)?.phase) {
                    KomgaReadStatusPhase.SYNCED -> KomgaProgressDispatchResult.SYNCED
                    KomgaReadStatusPhase.PENDING -> KomgaProgressDispatchResult.PENDING
                    KomgaReadStatusPhase.HELD -> KomgaProgressDispatchResult.HELD
                    KomgaReadStatusPhase.CONFLICT -> KomgaProgressDispatchResult.CONFLICT
                    KomgaReadStatusPhase.DENIED -> KomgaProgressDispatchResult.DENIED
                    null -> KomgaProgressDispatchResult.IDLE
                }
            }
            remote == before -> return KomgaProgressDispatchResult.AWAITING_CONFIRMATION
            else -> KomgaProgressDispatchResult.CONFLICT
        }
        repository.get(event.key)?.takeIf { it.revision == event.revision }?.let {
            repository.transition(
                it,
                if (result ==
                    KomgaProgressDispatchResult.HELD
                ) {
                    KomgaReadStatusPhase.HELD
                } else {
                    KomgaReadStatusPhase.CONFLICT
                },
                checkOwner,
            )
        }
        // Retain the request: an error or an unchanged GET does not prove the request cannot still finish.
        return result
    }
}
