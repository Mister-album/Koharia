package koharia.komga.progress

import eu.kanade.tachiyomi.network.HttpException
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import kotlinx.coroutines.CancellationException

internal interface KomgaReadStatusTransport : KomgaPageTransport {
    suspend fun mark(read: Boolean)
}

/** Both request journals share one ordering boundary; recording desired state never discards either request. */
internal class KomgaReadStatusDispatcher(
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
) {
    suspend fun dispatch(
        key: KomgaReadStatusKey,
        transport: KomgaReadStatusTransport,
        checkOwner: () -> Unit,
    ): KomgaProgressDispatchResult = ordering.coordinate(key, checkOwner) {
        suspend fun transition(phase: KomgaReadStatusPhase) {
            intents.get(key)?.let { intents.transition(it, phase, checkOwner) }
        }
        var readFailure = KomgaProgressDispatchResult.DENIED
        suspend fun read(): KomgaProgressBaseline? = try {
            transport.read().also { checkOwner() }
        } catch (error: HttpException) {
            val phase = error.progressFailurePhase() ?: throw error
            checkOwner()
            transition(phase)
            if (pages.attempt(key) != null) {
                pages.get(key)?.let { pages.transition(it, phase, checkOwner) }
            }
            readFailure = phase.failureResult()
            null
        }
        val pageAttempt = pages.attempt(key)
        val explicitAttempt = intents.attempt(key)
        if (pageAttempt != null && explicitAttempt != null) {
            transition(KomgaReadStatusPhase.HELD)
            return@coordinate KomgaProgressDispatchResult.HELD
        }
        if (pageAttempt != null) {
            val remote = read() ?: return@coordinate readFailure
            val result = KomgaPageDispatcher(pages, ordering).reconcile(pageAttempt, remote, checkOwner)
            if (result == KomgaProgressDispatchResult.CONFLICT || result == KomgaProgressDispatchResult.HELD) {
                transition(
                    if (result == KomgaProgressDispatchResult.CONFLICT) {
                        KomgaReadStatusPhase.CONFLICT
                    } else {
                        KomgaReadStatusPhase.HELD
                    },
                )
            }
            // Even a confirmed old page never sends a newer explicit action in this invocation.
            return@coordinate if (pages.attempt(key) == null) currentResult(key) else result
        }
        if (explicitAttempt != null) {
            val remote = read() ?: return@coordinate readFailure
            return@coordinate reconcile(explicitAttempt, remote, checkOwner)
        }
        val intent = intents.get(key) ?: return@coordinate KomgaProgressDispatchResult.IDLE
        if (intent.phase != KomgaReadStatusPhase.PENDING) return@coordinate currentResult(key)
        val baseline = KomgaProgressBaseline.decode(intent.baseline)
        if (baseline == null || baseline.bookId != key.bookId) {
            intents.transition(intent, KomgaReadStatusPhase.HELD, checkOwner)
            return@coordinate KomgaProgressDispatchResult.HELD
        }
        val before = read() ?: return@coordinate readFailure
        if (before != baseline) {
            intents.transition(intent, KomgaReadStatusPhase.CONFLICT, checkOwner)
            return@coordinate KomgaProgressDispatchResult.CONFLICT
        }
        val attempt = intents.begin(intent, before.encode(), checkOwner)
            ?: return@coordinate KomgaProgressDispatchResult.PENDING
        if (before.matchesReadStatusTarget(baseline, intent.read)) {
            return@coordinate reconcile(attempt, before, checkOwner)
        }
        val token = KomgaProgressRevision(key, intent.revision)
        try {
            ordering.checkCurrent(token)
            checkOwner()
            transport.mark(intent.read)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            val phase = error.progressFailurePhase() ?: throw error
            checkOwner()
            if (phase == KomgaReadStatusPhase.DENIED) {
                intents.settle(attempt, phase, null, checkOwner)
            } else {
                transition(phase)
            }
            return@coordinate currentResult(key)
        }
        checkOwner()
        // A newer local intent is allowed to exist; settle can only acknowledge this captured request.
        val confirmed = read() ?: return@coordinate readFailure
        reconcile(attempt, confirmed, checkOwner)
    }

    private suspend fun reconcile(
        attempt: KomgaReadStatusAttempt,
        remote: KomgaProgressBaseline,
        checkOwner: () -> Unit,
    ): KomgaProgressDispatchResult {
        val before = KomgaProgressBaseline.decode(attempt.baseline)
        val intent = attempt.intent
        val result = when {
            before == null || before.bookId != intent.key.bookId -> KomgaProgressDispatchResult.HELD
            remote.matchesReadStatusTarget(before, intent.read) -> {
                intents.settle(attempt, KomgaReadStatusPhase.SYNCED, remote.encode(), checkOwner)
                return currentResult(intent.key)
            }
            remote == before -> return KomgaProgressDispatchResult.AWAITING_CONFIRMATION
            else -> KomgaProgressDispatchResult.CONFLICT
        }
        intents.get(intent.key)?.let {
            intents.transition(
                it,
                if (result == KomgaProgressDispatchResult.CONFLICT) {
                    KomgaReadStatusPhase.CONFLICT
                } else {
                    KomgaReadStatusPhase.HELD
                },
                checkOwner,
            )
        }
        return result
    }

    private suspend fun currentResult(key: KomgaReadStatusKey) = when (intents.get(key)?.phase) {
        KomgaReadStatusPhase.SYNCED -> KomgaProgressDispatchResult.SYNCED
        KomgaReadStatusPhase.PENDING -> KomgaProgressDispatchResult.PENDING
        KomgaReadStatusPhase.CONFLICT -> KomgaProgressDispatchResult.CONFLICT
        KomgaReadStatusPhase.DENIED -> KomgaProgressDispatchResult.DENIED
        KomgaReadStatusPhase.HELD -> KomgaProgressDispatchResult.HELD
        null -> KomgaProgressDispatchResult.IDLE
    }
}
