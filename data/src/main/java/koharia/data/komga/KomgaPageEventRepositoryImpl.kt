package koharia.data.komga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaProgressSyncStatus
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class KomgaPageEventRepositoryImpl(private val database: Database) : KomgaPageEventRepository {
    private val queries get() = database.komga_page_eventQueries

    override fun observeSyncStatus(connectionId: Long, ownerKey: String, bookIds: Collection<String>) =
        bookIds.distinct().chunked(200).map { books ->
            queries.getSyncStatusForBooks(connectionId, books).subscribeToList().map { rows ->
                rows.groupBy { it.book_id }.mapValues { (_, bookRows) ->
                    bookRows.groupBy { it.owner_key }.mapNotNull { (owner, states) ->
                        val intent = states.firstOrNull { it.kind == "intent" }?.phase?.takeUnless { it == "SYNCED" }
                        val page = states.firstOrNull { it.kind == "page" }?.phase?.takeUnless { it == "SYNCED" }
                        val request = states.any { it.kind == "request" }
                        val status = when (intent ?: page) {
                            "HELD" -> KomgaProgressSyncStatus.HELD
                            "CONFLICT" -> KomgaProgressSyncStatus.CONFLICT
                            "DENIED" -> KomgaProgressSyncStatus.DENIED
                            "PENDING" -> if (request) {
                                KomgaProgressSyncStatus.VERIFYING
                            } else {
                                KomgaProgressSyncStatus.PENDING
                            }
                            null -> if (request) KomgaProgressSyncStatus.VERIFYING else null
                            else -> KomgaProgressSyncStatus.HELD
                        }
                        status?.let { if (owner == ownerKey) it else KomgaProgressSyncStatus.UNVERIFIED_OWNER }
                    }.toSet()
                }.filterValues { it.isNotEmpty() }
            }
        }.let { batches ->
            when (batches.size) {
                0 -> flowOf(emptyMap())
                1 -> batches.single()
                else -> combine(batches) { it.fold(emptyMap<String, Set<KomgaProgressSyncStatus>>()) { a, b -> a + b } }
            }
        }

    override suspend fun recoveryKeys(): List<KomgaReadStatusKey> =
        queries.getRecoveryKeys(::KomgaReadStatusKey).awaitAsList()

    override suspend fun unsettledStates(): List<KomgaPageState> = database.transactionWithResult {
        queries.getUnsettledKeys().awaitAsList().groupBy { it.connection_id to it.owner_key }
            .flatMap { (owner, keys) -> states(owner.first, owner.second, keys.map { it.book_id }).values }
    }

    override suspend fun restoreHeld(states: List<KomgaPageState>) = database.transaction {
        val keys = states.map { checkNotNull(it.event?.key ?: it.attempt?.event?.key) }
        require(keys.distinct().size == keys.size)
        states.forEach { state ->
            fun validate(event: KomgaPageEvent) {
                require(
                    event.key.connectionId > 0 && event.key.ownerKey.startsWith("restored:") &&
                        event.generation > 0 && event.revision >= 0 && event.total > 0 &&
                        event.page in 0 until event.total && event.readAt >= 0 && event.baseline == null &&
                        event.supersededByRevision == null,
                )
            }
            state.event?.let { event ->
                validate(event)
                require(event.phase == KomgaReadStatusPhase.HELD)
                queries.restoreHeldEvent(
                    event.key.connectionId,
                    event.key.ownerKey,
                    event.key.bookId,
                    event.generation,
                    event.revision,
                    event.page,
                    event.total,
                    event.readAt,
                )
            }
            state.attempt?.let { request ->
                val event = request.event
                validate(event)
                require(event.phase == KomgaReadStatusPhase.PENDING && request.baseline == "restored:unknown")
                require(state.event?.key?.let { it == event.key } != false)
                queries.restoreHeldAttempt(
                    event.key.connectionId,
                    event.key.ownerKey,
                    event.key.bookId,
                    event.generation,
                    event.revision,
                    event.page,
                    event.total,
                    event.readAt,
                )
            }
        }
    }

    override suspend fun get(key: KomgaReadStatusKey) =
        queries.getEvent(key.connectionId, key.ownerKey, key.bookId, ::mapEvent).awaitAsOneOrNull()

    override suspend fun attempt(key: KomgaReadStatusKey) =
        queries.getAttempt(key.connectionId, key.ownerKey, key.bookId, ::mapAttempt).awaitAsOneOrNull()

    override suspend fun pending(connectionId: Long, ownerKey: String) =
        queries.getPending(connectionId, ownerKey, ::mapEvent).awaitAsList()

    override suspend fun hasUnsettled(connectionId: Long, ownerKey: String) =
        queries.getUnsettledBook(connectionId, ownerKey).awaitAsOneOrNull() != null

    override suspend fun states(
        connectionId: Long,
        ownerKey: String,
        bookIds: Collection<String>,
    ): Map<String, KomgaPageState> = database.transactionWithResult {
        buildMap {
            bookIds.distinct().chunked(900).forEach { books ->
                val events = queries.getEventsForBooks(connectionId, ownerKey, books, ::mapEvent)
                    .awaitAsList().associateBy { it.key.bookId }
                val attempts = queries.getAttemptsForBooks(connectionId, ownerKey, books, ::mapAttempt)
                    .awaitAsList().associateBy { it.event.key.bookId }
                books.forEach { put(it, KomgaPageState(events[it], attempts[it])) }
            }
        }
    }

    override suspend fun record(
        key: KomgaReadStatusKey,
        revision: Long,
        page: Long,
        total: Long,
        readAt: Long,
        baseline: String?,
        checkOwner: () -> Unit,
    ): KomgaPageEvent? = database.transactionWithResult {
        require(revision >= 0 && total > 0 && page in 0 until total && readAt >= 0)
        checkOwner()
        if (!isRevisionCurrent(key, revision)) return@transactionWithResult null
        val previous = get(key)
        val generation = previous?.generation ?: 0
        check(generation < Long.MAX_VALUE) { "Page event generation exhausted" }
        val event = KomgaPageEvent(
            key,
            generation + 1,
            revision,
            page,
            total,
            readAt,
            when {
                previous == null || previous.revision != revision -> baseline
                else -> previous.baseline
            },
            previous?.phase?.takeIf {
                previous.revision == revision && it in setOf(
                    KomgaReadStatusPhase.CONFLICT,
                    KomgaReadStatusPhase.DENIED,
                    KomgaReadStatusPhase.HELD,
                )
            } ?: KomgaReadStatusPhase.PENDING,
        )
        put(event)
        checkOwner()
        event
    }

    override suspend fun reconcileLocal(
        key: KomgaReadStatusKey,
        revision: Long,
        expected: KomgaPageState,
        page: Long,
        total: Long,
        readAt: Long,
        baseline: String,
        keepLocal: Boolean,
        checkOwner: () -> Unit,
    ): KomgaPageEvent? = database.transactionWithResult {
        require(total > 0 && page in 0 until total && readAt >= 0 && baseline.isNotBlank())
        checkOwner()
        if (!isRevisionCurrent(key, revision) || expected.attempt != null ||
            KomgaPageState(get(key), attempt(key)) != expected ||
            database.komga_read_statusQueries.getAttempt(
                key.connectionId,
                key.ownerKey,
                key.bookId,
            ).awaitAsOneOrNull() !=
            null
        ) {
            return@transactionWithResult null
        }
        val generation = expected.event?.generation ?: 0
        check(generation < Long.MAX_VALUE)
        val event = KomgaPageEvent(
            key,
            generation + 1,
            revision,
            page,
            total,
            readAt,
            baseline,
            if (keepLocal) KomgaReadStatusPhase.PENDING else KomgaReadStatusPhase.SYNCED,
        )
        put(event)
        checkOwner()
        event
    }

    override suspend fun begin(
        event: KomgaPageEvent,
        baseline: String,
        checkOwner: () -> Unit,
    ): KomgaPageAttempt? = database.transactionWithResult {
        require(baseline.isNotBlank())
        checkOwner()
        if (event.supersededByRevision != null || event.phase != KomgaReadStatusPhase.PENDING || get(
                event.key,
            ) != event ||
            !isRevisionCurrent(event.key, event.revision) || attempt(event.key) != null ||
            database.komga_read_statusQueries.getAttempt(
                event.key.connectionId,
                event.key.ownerKey,
                event.key.bookId,
            ).awaitAsOneOrNull() != null
        ) {
            return@transactionWithResult null
        }
        queries.putAttempt(
            event.key.connectionId, event.key.ownerKey, event.key.bookId, event.generation, event.revision,
            event.page, event.total, event.readAt, event.baseline, baseline,
        )
        checkOwner()
        KomgaPageAttempt(event, baseline)
    }

    override suspend fun settle(
        attempt: KomgaPageAttempt,
        phase: KomgaReadStatusPhase,
        confirmedBaseline: String?,
        checkOwner: () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(phase != KomgaReadStatusPhase.PENDING)
        require(phase != KomgaReadStatusPhase.SYNCED || !confirmedBaseline.isNullOrBlank())
        checkOwner()
        val sent = attempt.event
        if (attempt(sent.key) != attempt) return@transactionWithResult false
        val explicit = database.komga_read_statusQueries.getIntent(
            sent.key.connectionId,
            sent.key.ownerKey,
            sent.key.bookId,
        ).awaitAsOneOrNull()
        if (phase == KomgaReadStatusPhase.SYNCED && explicit != null && explicit.revision > sent.revision &&
            explicit.phase == "PENDING" && explicit.baseline == attempt.baseline
        ) {
            database.komga_read_statusQueries.advanceConfirmedBaseline(
                confirmedBaseline,
                sent.key.connectionId,
                sent.key.ownerKey,
                sent.key.bookId,
                explicit.revision,
                attempt.baseline,
            )
        }
        val current = get(sent.key)
        if (current != null && current.phase == KomgaReadStatusPhase.PENDING &&
            current.revision == sent.revision && isRevisionCurrent(sent.key, sent.revision)
        ) {
            put(
                current.copy(
                    phase = phase.takeIf {
                        current.generation == sent.generation || phase != KomgaReadStatusPhase.SYNCED
                    } ?: current.phase,
                    baseline = confirmedBaseline.takeIf { phase == KomgaReadStatusPhase.SYNCED } ?: current.baseline,
                ),
            )
        }
        queries.removeAttempt(sent.key.connectionId, sent.key.ownerKey, sent.key.bookId, sent.generation)
        checkOwner()
        true
    }

    override suspend fun holdConnection(connectionId: Long) {
        queries.holdConnection(connectionId)
    }

    override suspend fun transition(
        event: KomgaPageEvent,
        phase: KomgaReadStatusPhase,
        checkOwner: () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(phase in setOf(KomgaReadStatusPhase.CONFLICT, KomgaReadStatusPhase.DENIED, KomgaReadStatusPhase.HELD))
        checkOwner()
        if (event.supersededByRevision != null || get(event.key) != event ||
            !isRevisionCurrent(event.key, event.revision)
        ) {
            return@transactionWithResult false
        }
        put(event.copy(phase = phase))
        checkOwner()
        true
    }

    override suspend fun removeConnection(connectionId: Long) = database.transaction {
        queries.removeConnectionAttempts(connectionId)
        queries.removeConnectionEvents(connectionId)
    }

    private suspend fun isRevisionCurrent(key: KomgaReadStatusKey, revision: Long): Boolean {
        val intent = database.komga_read_statusQueries
            .getIntent(key.connectionId, key.ownerKey, key.bookId).awaitAsOneOrNull()
        return (intent?.revision ?: 0) == revision && (intent == null || intent.phase == "SYNCED")
    }

    private suspend fun put(event: KomgaPageEvent) {
        queries.putEvent(
            event.key.connectionId, event.key.ownerKey, event.key.bookId, event.generation, event.revision,
            event.page, event.total, event.readAt, event.baseline, event.phase.name,
            event.supersededByRevision,
        )
    }

    private fun mapEvent(
        connectionId: Long,
        ownerKey: String,
        bookId: String,
        generation: Long,
        revision: Long,
        page: Long,
        total: Long,
        readAt: Long,
        baseline: String?,
        phase: String,
        supersededByRevision: Long? = null,
    ) = KomgaPageEvent(
        KomgaReadStatusKey(connectionId, ownerKey, bookId),
        generation,
        revision,
        page,
        total,
        readAt,
        baseline,
        KomgaReadStatusPhase.valueOf(phase),
        supersededByRevision,
    )

    private fun mapAttempt(
        connectionId: Long,
        ownerKey: String,
        bookId: String,
        generation: Long,
        revision: Long,
        page: Long,
        total: Long,
        readAt: Long,
        eventBaseline: String?,
        baseline: String,
    ) = KomgaPageAttempt(
        mapEvent(connectionId, ownerKey, bookId, generation, revision, page, total, readAt, eventBaseline, "PENDING"),
        baseline,
    )
}
