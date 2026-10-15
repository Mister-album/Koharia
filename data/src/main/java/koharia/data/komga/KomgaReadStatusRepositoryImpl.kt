package koharia.data.komga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOneOrNull

class KomgaReadStatusRepositoryImpl(private val database: Database) : KomgaReadStatusRepository {
    private val queries get() = database.komga_read_statusQueries

    override suspend fun get(key: KomgaReadStatusKey) =
        queries.getIntent(key.connectionId, key.ownerKey, key.bookId, ::mapIntent).awaitAsOneOrNull()

    override fun observe(key: KomgaReadStatusKey) =
        queries.getIntent(key.connectionId, key.ownerKey, key.bookId, ::mapIntent).subscribeToOneOrNull()

    override suspend fun prepareBaseline(
        intent: KomgaReadStatusIntent,
        baseline: String,
        checkOwner: () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(intent.baseline == null && baseline.isNotBlank() && !intent.key.ownerKey.startsWith("restored:"))
        checkOwner()
        val key = intent.key
        val current = get(key) ?: return@transactionWithResult false
        if (current.copy(phase = intent.phase) != intent ||
            current.phase !in setOf(KomgaReadStatusPhase.PENDING, KomgaReadStatusPhase.HELD) ||
            attempt(key) != null ||
            database.komga_page_eventQueries.getAttempt(
                key.connectionId,
                key.ownerKey,
                key.bookId,
            ).awaitAsOneOrNull() !=
            null
        ) {
            return@transactionWithResult false
        }
        queries.prepareBaseline(baseline, key.connectionId, key.ownerKey, key.bookId, intent.revision)
        checkOwner()
        true
    }

    override suspend fun pending(connectionId: Long, ownerKey: String) =
        queries.getPending(connectionId, ownerKey, ::mapIntent).awaitAsList()

    override suspend fun forOwner(connectionId: Long, ownerKey: String) =
        queries.getForOwner(connectionId, ownerKey, ::mapIntent).awaitAsList()

    override suspend fun unsettled() = queries.getUnsettled(::mapIntent).awaitAsList()

    override suspend fun attempt(key: KomgaReadStatusKey) =
        queries.getAttempt(key.connectionId, key.ownerKey, key.bookId, ::mapAttempt).awaitAsOneOrNull()

    override suspend fun attempts(connectionId: Long, ownerKey: String) =
        queries.getAttempts(connectionId, ownerKey, ::mapAttempt).awaitAsList()

    override suspend fun allAttempts() = queries.getAllAttempts(::mapAttempt).awaitAsList()

    override suspend fun restoreHeldAttempts(attempts: List<KomgaReadStatusAttempt>) = database.transaction {
        require(attempts.map { it.intent.key }.distinct().size == attempts.size)
        attempts.forEach { request ->
            val intent = request.intent
            require(
                intent.key.connectionId > 0 && intent.key.ownerKey.startsWith("restored:") &&
                    intent.revision > 0 && intent.requestedAt >= 0 && intent.baseline == null &&
                    intent.phase == KomgaReadStatusPhase.PENDING && request.baseline == "restored:unknown",
            )
            queries.restoreHeldAttempt(
                intent.key.connectionId,
                intent.key.ownerKey,
                intent.key.bookId,
                intent.revision,
                intent.read,
                intent.requestedAt,
            )
        }
    }

    override suspend fun attemptsForBooks(
        connectionId: Long,
        ownerKey: String,
        bookIds: Collection<String>,
    ): Map<String, KomgaReadStatusAttempt?> = database.transactionWithResult {
        buildMap {
            bookIds.distinct().chunked(900).forEach { books ->
                val requests = queries.getAttemptsForBooks(connectionId, ownerKey, books, ::mapAttempt)
                    .awaitAsList().associateBy { it.intent.key.bookId }
                books.forEach { put(it, requests[it]) }
            }
        }
    }

    override suspend fun begin(
        intent: KomgaReadStatusIntent,
        baseline: String,
        checkOwner: () -> Unit,
    ): KomgaReadStatusAttempt? = database.transactionWithResult {
        require(baseline.isNotBlank())
        checkOwner()
        if (intent.phase != KomgaReadStatusPhase.PENDING || get(intent.key) != intent || attempt(intent.key) != null ||
            database.komga_page_eventQueries.getAttempt(
                intent.key.connectionId,
                intent.key.ownerKey,
                intent.key.bookId,
            ).awaitAsOneOrNull() != null
        ) {
            return@transactionWithResult null
        }
        queries.putAttempt(
            intent.key.connectionId,
            intent.key.ownerKey,
            intent.key.bookId,
            intent.revision,
            intent.read,
            intent.requestedAt,
            intent.baseline,
            baseline,
        )
        checkOwner()
        KomgaReadStatusAttempt(intent, baseline)
    }

    override suspend fun settle(
        attempt: KomgaReadStatusAttempt,
        phase: KomgaReadStatusPhase,
        confirmedBaseline: String?,
        checkOwner: () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(phase != KomgaReadStatusPhase.PENDING)
        require(phase != KomgaReadStatusPhase.SYNCED || !confirmedBaseline.isNullOrBlank())
        checkOwner()
        val sent = attempt.intent
        if (attempt(sent.key) != attempt) return@transactionWithResult false
        val current = get(sent.key)
        if (current?.phase == KomgaReadStatusPhase.PENDING) {
            if (current.revision == sent.revision) {
                if (phase == KomgaReadStatusPhase.SYNCED) {
                    queries.confirmIntent(
                        confirmedBaseline,
                        sent.key.connectionId,
                        sent.key.ownerKey,
                        sent.key.bookId,
                        sent.revision,
                    )
                } else {
                    queries.finishIntent(
                        phase.name,
                        sent.key.connectionId,
                        sent.key.ownerKey,
                        sent.key.bookId,
                        sent.revision,
                    )
                }
            } else if (phase == KomgaReadStatusPhase.SYNCED && current.baseline == attempt.baseline) {
                queries.advanceConfirmedBaseline(
                    confirmedBaseline,
                    sent.key.connectionId,
                    sent.key.ownerKey,
                    sent.key.bookId,
                    current.revision,
                    attempt.baseline,
                )
            }
        }
        if (phase == KomgaReadStatusPhase.SYNCED) {
            database.komga_page_eventQueries.supersedeBeforeRevision(
                sent.revision,
                sent.key.connectionId,
                sent.key.ownerKey,
                sent.key.bookId,
            )
        }
        queries.removeAttempt(sent.key.connectionId, sent.key.ownerKey, sent.key.bookId, sent.revision)
        checkOwner()
        true
    }

    override suspend fun transition(
        intent: KomgaReadStatusIntent,
        phase: KomgaReadStatusPhase,
        checkOwner: () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(phase in setOf(KomgaReadStatusPhase.CONFLICT, KomgaReadStatusPhase.DENIED, KomgaReadStatusPhase.HELD))
        checkOwner()
        if (intent.phase != KomgaReadStatusPhase.PENDING ||
            get(intent.key) != intent
        ) {
            return@transactionWithResult false
        }
        queries.finishIntent(
            phase.name,
            intent.key.connectionId,
            intent.key.ownerKey,
            intent.key.bookId,
            intent.revision,
        )
        checkOwner()
        true
    }

    override suspend fun restoreHeld(intents: List<KomgaReadStatusIntent>) {
        require(intents.map { it.key }.distinct().size == intents.size)
        require(
            intents.all {
                it.key.connectionId > 0 && it.key.ownerKey.startsWith("restored:") &&
                    it.revision > 0 && it.requestedAt >= 0 && it.baseline == null &&
                    it.phase == KomgaReadStatusPhase.HELD
            },
        )
        database.transaction {
            intents.forEach {
                queries.restoreHeld(
                    it.key.connectionId,
                    it.key.ownerKey,
                    it.key.bookId,
                    it.revision,
                    it.read,
                    it.requestedAt,
                )
            }
        }
    }

    override suspend fun record(
        key: KomgaReadStatusKey,
        read: Boolean,
        requestedAt: Long,
        baseline: String?,
        chapters: List<KomgaReadStatusChapter>,
        checkOwner: () -> Unit,
    ): KomgaReadStatusIntent = database.transactionWithResult {
        require(requestedAt >= 0)
        checkOwner()
        val aliases = chapters.distinctBy(KomgaReadStatusChapter::id)
        require(chapters.groupBy { it.id }.all { (_, values) -> values.map { it.url }.distinct().size == 1 })
        aliases.forEach { chapter ->
            val owner = queries.getChapterOwner(chapter.id).awaitAsOneOrNull()
            check(owner?.source == key.connectionId && owner.url == chapter.url) { "Chapter ownership changed" }
        }
        val previousRevision = get(key)?.revision ?: 0
        check(previousRevision < Long.MAX_VALUE) { "Read-status revision exhausted" }
        val revision = previousRevision + 1
        queries.putIntent(key.connectionId, key.ownerKey, key.bookId, revision, read, requestedAt, baseline)
        if (aliases.isNotEmpty()) {
            val ids = aliases.map(KomgaReadStatusChapter::id)
            queries.applyReadStatus(read, ids, key.connectionId)
            if (!read) {
                queries.clearEpubProgress(ids)
                queries.clearRemoteEpubProgress(ids)
                queries.clearTtsProgress(ids)
            }
        }
        checkOwner()
        KomgaReadStatusIntent(key, revision, read, requestedAt, baseline, KomgaReadStatusPhase.PENDING)
    }

    override suspend fun finish(key: KomgaReadStatusKey, revision: Long, phase: KomgaReadStatusPhase): Boolean =
        database.transactionWithResult {
            require(phase != KomgaReadStatusPhase.PENDING)
            queries.finishIntent(phase.name, key.connectionId, key.ownerKey, key.bookId, revision)
            queries.changedRows().awaitAsOne() == 1L
        }

    override suspend fun holdConnection(connectionId: Long) {
        queries.holdConnection(connectionId)
    }
    override suspend fun removeConnection(connectionId: Long) = database.transaction {
        queries.removeConnectionAttempts(connectionId)
        queries.removeConnection(connectionId)
    }

    private fun mapAttempt(
        connectionId: Long,
        ownerKey: String,
        bookId: String,
        revision: Long,
        read: Boolean,
        requestedAt: Long,
        intentBaseline: String?,
        baseline: String,
    ) = KomgaReadStatusAttempt(
        mapIntent(connectionId, ownerKey, bookId, revision, read, requestedAt, intentBaseline, "PENDING"),
        baseline,
    )

    private fun mapIntent(
        connectionId: Long,
        ownerKey: String,
        bookId: String,
        revision: Long,
        read: Boolean,
        requestedAt: Long,
        baseline: String?,
        phase: String,
    ) = KomgaReadStatusIntent(
        KomgaReadStatusKey(connectionId, ownerKey, bookId),
        revision,
        read,
        requestedAt,
        baseline,
        KomgaReadStatusPhase.valueOf(phase),
    )
}
