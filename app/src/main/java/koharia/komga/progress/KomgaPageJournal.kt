package koharia.komga.progress

import koharia.connection.ConnectionPageProgressDecision
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import java.io.IOException

internal class KomgaPageJournal(
    private val database: Database,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
    private val wakeups: KomgaProgressWakeups,
) {
    suspend fun state(key: KomgaReadStatusKey) = pages.states(key.connectionId, key.ownerKey, listOf(key.bookId))
        .getValue(key.bookId)

    suspend fun record(
        token: KomgaProgressRevision,
        baseline: String?,
        page: Int,
        total: Int,
        readAt: Long,
        initial: Boolean,
        checkOwner: () -> Unit,
        commitChapter: suspend () -> Unit,
    ) {
        val event = database.transactionWithResult {
            checkOwner()
            ordering.checkCurrent(token)
            val recorded = if (!initial && total > 0 && page in 0 until total) {
                checkNotNull(
                    pages.record(
                        token.key,
                        token.revision,
                        page.toLong(),
                        total.toLong(),
                        readAt,
                        baseline,
                        checkOwner,
                    ),
                )
                    .also {
                        if (it.baseline == null) pages.transition(it, KomgaReadStatusPhase.HELD, checkOwner)
                    }
            } else {
                null
            }
            commitChapter()
            checkOwner()
            ordering.checkCurrent(token)
            recorded
        }
        if (event?.baseline != null && event.phase == KomgaReadStatusPhase.PENDING) wake(token.key)
    }

    suspend fun compare(
        token: KomgaProgressRevision,
        expected: KomgaPageState,
        baseline: KomgaProgressBaseline,
        page: Int,
        total: Int,
        readAt: Long,
        decision: ConnectionPageProgressDecision,
        checkOwner: () -> Unit,
        verifyRemote: suspend () -> Unit,
        commitChapter: suspend () -> Unit,
    ) {
        if (!baseline.valid || baseline.bookId != token.key.bookId || baseline.total != total ||
            (baseline.epub && !baseline.divina) || page !in 0 until total ||
            (decision == ConnectionPageProgressDecision.COMPARED && !expected.settled)
        ) {
            throw IOException("Komga progress comparison requires confirmation")
        }
        val remotePage = baseline.page?.minus(1) ?: if (baseline.completed) total - 1 else 0
        val keepLocal = decision == ConnectionPageProgressDecision.KEEP_LOCAL ||
            (decision == ConnectionPageProgressDecision.USE_REMOTE && page != remotePage)
        val acceptedPage = if (decision == ConnectionPageProgressDecision.COMPARED) remotePage else page
        ordering.coordinate(token.key, checkOwner) {
            verifyRemote()
            database.transaction {
                ordering.checkCurrent(token)
                if (state(token.key) != expected) throw IOException("Komga progress changed during comparison")
                val reviewed = expected.attempt?.let { attempt ->
                    check(decision != ConnectionPageProgressDecision.COMPARED)
                    if (!pages.settle(attempt, KomgaReadStatusPhase.HELD, null, checkOwner)) {
                        throw IOException("Komga request changed during comparison")
                    }
                    state(token.key)
                } ?: expected
                if (pages.reconcileLocal(
                        token.key, token.revision, reviewed,
                        acceptedPage.toLong(), total.toLong(), readAt.coerceAtLeast(0),
                        baseline.encode(), keepLocal, checkOwner,
                    ) == null
                ) {
                    throw IOException("Komga progress changed during comparison")
                }
                commitChapter()
                checkOwner()
                ordering.checkCurrent(token)
            }
        }
        if (keepLocal) wake(token.key)
    }

    suspend fun wake(key: KomgaReadStatusKey) {
        try {
            wakeups.wake(key)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Komga progress is committed; startup recovery will retry scheduling" }
        }
    }
}
