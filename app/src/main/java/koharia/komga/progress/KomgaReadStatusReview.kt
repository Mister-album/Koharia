package koharia.komga.progress

import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import java.io.IOException

/** A foreground decision replaces only the exact local and server states that the user reviewed. */
internal class KomgaReadStatusReview(
    private val database: Database,
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
) {
    data class Local(
        val intent: KomgaReadStatusIntent?,
        val pages: KomgaPageState,
        val attempt: KomgaReadStatusAttempt?,
        val chapters: List<Chapter>,
    )

    data class Comparison(val key: KomgaReadStatusKey, val local: Local, val remote: KomgaProgressBaseline)

    private suspend fun capture(key: KomgaReadStatusKey, chapters: suspend () -> List<Chapter>) =
        database.transactionWithResult {
            Local(
                intents.get(key),
                pages.states(key.connectionId, key.ownerKey, listOf(key.bookId)).getValue(key.bookId),
                intents.attempt(key),
                chapters().sortedBy { it.id },
            )
        }

    suspend fun compare(
        key: KomgaReadStatusKey,
        transport: KomgaReadStatusTransport,
        checkOwner: () -> Unit,
        chapters: suspend () -> List<Chapter>,
    ): Comparison? = ordering.coordinate(key, checkOwner) {
        require(!key.ownerKey.startsWith("restored:"))
        val local = capture(key, chapters)
        if (local.intent == null || local.intent.phase == KomgaReadStatusPhase.SYNCED) return@coordinate null
        val remote = transport.read()
        checkOwner()
        if (!remote.valid || remote.bookId != key.bookId || capture(key, chapters) != local) changed()
        Comparison(key, local, remote)
    }

    suspend fun reissue(
        comparison: Comparison,
        transport: KomgaReadStatusTransport,
        checkOwner: () -> Unit,
        chapters: suspend () -> List<Chapter>,
    ): KomgaReadStatusIntent = ordering.coordinate(comparison.key, checkOwner) {
        val key = comparison.key
        require(!key.ownerKey.startsWith("restored:"))
        val expected = comparison.local
        val desired = checkNotNull(expected.intent)
        if (capture(key, chapters) != expected) changed()
        // Do not retire uncertain requests if the server changed while the dialog was open.
        if (transport.read() != comparison.remote) changed()
        checkOwner()
        database.transactionWithResult {
            if (capture(key, chapters) != expected) changed()
            checkOwner()
            expected.attempt?.let {
                if (!intents.settle(it, KomgaReadStatusPhase.HELD, null, checkOwner)) changed()
            }
            expected.pages.attempt?.let {
                if (!pages.settle(it, KomgaReadStatusPhase.HELD, null, checkOwner)) changed()
            }
            // Retirement is not an acknowledgement. Only the new explicit action may be dispatched.
            intents.record(
                key,
                desired.read,
                System.currentTimeMillis(),
                comparison.remote.encode(),
                expected.chapters.map { KomgaReadStatusChapter(it.id, it.url) },
                checkOwner,
            ).also { checkOwner() }
        }
    }

    private fun changed(): Nothing = throw IOException("Komga progress changed; review again")
}
