package koharia.komga.progress

import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository

/** One wakeup drains stored work; no caller can supply a replacement page payload. */
internal class KomgaProgressCoordinator(
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
) {
    suspend fun dispatch(
        key: KomgaReadStatusKey,
        transport: KomgaReadStatusTransport,
        checkOwner: () -> Unit,
    ): KomgaProgressDispatchResult {
        repeat(4) {
            checkOwner()
            val before = state(key)
            val result = try {
                if (before.pageAttempt != null || before.explicitAttempt != null ||
                    before.intent?.let { it.phase != KomgaReadStatusPhase.SYNCED } == true
                ) {
                    KomgaReadStatusDispatcher(intents, pages, ordering).dispatch(key, transport, checkOwner)
                } else {
                    KomgaPageDispatcher(pages, ordering).dispatch(ordering.capture(key), transport, checkOwner)
                }
            } catch (_: KomgaProgressSuperseded) {
                checkOwner()
                KomgaProgressDispatchResult.PENDING
            }
            if (result !in setOf(
                    KomgaProgressDispatchResult.PENDING,
                    KomgaProgressDispatchResult.SYNCED,
                    KomgaProgressDispatchResult.IDLE,
                )
            ) {
                return result
            }
            val after = state(key)
            checkOwner()
            if (!after.hasWork || after == before) return result
        }
        return KomgaProgressDispatchResult.PENDING
    }

    private suspend fun state(key: KomgaReadStatusKey) = State(
        intents.get(key),
        pages.get(key),
        intents.attempt(key),
        pages.attempt(key),
    )

    private data class State(
        val intent: KomgaReadStatusIntent?,
        val page: KomgaPageEvent?,
        val explicitAttempt: KomgaReadStatusAttempt?,
        val pageAttempt: KomgaPageAttempt?,
    ) {
        val hasWork: Boolean get() = explicitAttempt != null || pageAttempt != null ||
            intent?.phase == KomgaReadStatusPhase.PENDING ||
            (page?.phase == KomgaReadStatusPhase.PENDING && page.supersededByRevision == null)
    }
}
