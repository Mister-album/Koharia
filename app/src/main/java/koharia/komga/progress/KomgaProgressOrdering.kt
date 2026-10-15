package koharia.komga.progress

import koharia.connection.ConnectionAddressRouter
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

internal data class KomgaProgressRevision(val key: KomgaReadStatusKey, val revision: Long)

/** Fixed stripes bound memory; a collision only serializes otherwise independent books. */
internal class KomgaProgressOrdering(
    private val repository: KomgaReadStatusRepository,
    private val pages: koharia.domain.komga.KomgaPageEventRepository,
) {
    private val locks = Array(64) { Mutex() }
    private fun lock(key: KomgaReadStatusKey) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    suspend fun capture(key: KomgaReadStatusKey) = KomgaProgressRevision(key, repository.get(key)?.revision ?: 0)

    suspend fun checkCurrent(token: KomgaProgressRevision) {
        if ((repository.get(token.key)?.revision ?: 0) != token.revision) throw KomgaProgressSuperseded()
    }

    /** Durable recovery may resolve an older request while a newer explicit intent is pending. */
    suspend fun <T> coordinate(
        key: KomgaReadStatusKey,
        checkOwner: () -> Unit,
        operation: suspend () -> T,
    ): T = lock(key).withLock {
        checkOwner()
        operation().also { checkOwner() }
    }

    suspend fun <T> remote(
        token: KomgaProgressRevision,
        checkOwner: () -> Unit,
        allowPageAttemptRecovery: Boolean = false,
        operation: suspend () -> T,
    ): T = lock(token.key).withLock {
        checkOwner()
        val current = repository.get(token.key)
        if ((current?.revision ?: 0) != token.revision) throw KomgaProgressSuperseded()
        if (current != null && current.phase != KomgaReadStatusPhase.SYNCED) throw KomgaProgressOnHold()
        if (repository.attempt(token.key) != null || (!allowPageAttemptRecovery && pages.attempt(token.key) != null)) {
            throw KomgaProgressOnHold()
        }
        operation().also {
            checkOwner()
            checkCurrent(token)
        }
    }

    suspend fun record(
        key: KomgaReadStatusKey,
        read: Boolean,
        requestedAt: Long,
        baseline: String?,
        chapters: List<KomgaReadStatusChapter>,
        checkOwner: () -> Unit,
    ) = repository.record(key, read, requestedAt, baseline, chapters, checkOwner)

    /** Compatibility boundary: a legacy series/readlist PUT can affect any book in that collection. */
    suspend fun <T> legacyAggregate(
        connectionId: Long,
        ownerKey: String,
        checkOwner: () -> Unit,
        operation: suspend () -> T,
    ): T = allLocks(0) {
        checkOwner()
        if (repository.forOwner(connectionId, ownerKey).any { it.phase != KomgaReadStatusPhase.SYNCED } ||
            repository.attempts(connectionId, ownerKey).isNotEmpty()
        ) {
            throw KomgaProgressOnHold()
        }
        operation().also { checkOwner() }
    }

    private suspend fun <T> allLocks(index: Int, operation: suspend () -> T): T =
        if (index == locks.size) operation() else locks[index].withLock { allLocks(index + 1, operation) }

    suspend fun dispatch(
        intent: KomgaReadStatusIntent,
        checkOwner: () -> Unit,
        operation: suspend () -> KomgaReadStatusPhase,
    ): Boolean = lock(intent.key).withLock {
        checkOwner()
        val current = repository.get(intent.key)
        if (current?.revision != intent.revision || current.phase != KomgaReadStatusPhase.PENDING) return@withLock false
        val phase = operation()
        checkOwner()
        repository.finish(intent.key, intent.revision, phase)
    }

    companion object {
        fun key(connectionId: Long, ownerKey: String, bookUrl: String): KomgaReadStatusKey =
            KomgaReadStatusKey(connectionId, ownerKey, bookId(bookUrl))

        fun bookId(bookUrl: String): String {
            val segments = bookUrl.toHttpUrl().pathSegments.dropLastWhile(String::isEmpty)
            require(segments.size >= 4 && segments.takeLast(4).take(3) == listOf("api", "v1", "books")) {
                "Expected a Komga book resource"
            }
            return segments.last().also { require(it.isNotBlank()) { "Expected a Komga book identity" } }
        }
    }
}

internal class KomgaProgressSuperseded : CancellationException("Komga reading intent superseded this session")

internal class KomgaProgressOnHold :
    IOException("Komga explicit reading intent requires synchronization"),
    ConnectionAddressRouter.NonRoutingFailure

internal class KomgaProgressRevisionMissing : IOException("Komga queued progress has no captured revision")
