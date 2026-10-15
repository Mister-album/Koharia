package koharia.komga

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.data.komga.KomgaReadStatusRepositoryImpl
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaReadStatusAttemptDeviceTest {
    private val repository get() = KomgaReadStatusRepositoryImpl(Injekt.get<Database>())

    @Test
    fun restoredRequestsStayIsolatedAndInvalidImportRollsBackAllRows(): Unit = fixture { original ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        val key = original.copy(ownerKey = "restored:${"a".repeat(64)}:0")
        val old = koharia.domain.komga.KomgaPageEvent(key, 2, 0, 3, 10, 100, null, KomgaReadStatusPhase.PENDING)
        val state = koharia.domain.komga.KomgaPageState(
            old.copy(generation = 3, page = 5, phase = KomgaReadStatusPhase.HELD),
            koharia.domain.komga.KomgaPageAttempt(old, "restored:unknown"),
        )
        val request = koharia.domain.komga.KomgaReadStatusAttempt(
            koharia.domain.komga.KomgaReadStatusIntent(
                key.copy(bookId = "explicit"),
                1,
                true,
                99,
                null,
                KomgaReadStatusPhase.PENDING,
            ),
            "restored:unknown",
        )
        try {
            val invalid = request.copy(intent = request.intent.copy(key = original))
            assertTrue(
                runCatching {
                    Injekt.get<Database>().transaction {
                        pages.restoreHeld(listOf(state))
                        repository.restoreHeldAttempts(listOf(request, invalid))
                    }
                }.isFailure,
            )
            assertNull(pages.get(key))
            assertNull(pages.attempt(key))
            assertNull(repository.attempt(request.intent.key))
            repeat(2) {
                pages.restoreHeld(listOf(state))
                repository.restoreHeldAttempts(listOf(request))
            }
            assertEquals(state, pages.states(key.connectionId, key.ownerKey, listOf(key.bookId)).getValue(key.bookId))
            assertEquals(request, repository.attempt(request.intent.key))
            assertTrue(pages.pending(key.connectionId, key.ownerKey).isEmpty())
            assertNull(pages.get(original))
            assertNull(repository.attempt(original.copy(bookId = "explicit")))
            assertEquals(state, pages.unsettledStates().single { it.event?.key == key })
            assertEquals(request, repository.allAttempts().single { it.intent.key == request.intent.key })
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun confirmedExplicitActionSupersedesOlderPagesWithoutAcknowledgingTheirPayload(): Unit = fixture { key ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            val old = checkNotNull(pages.record(key, 0, 3, 10, 100, "opening") {})
            val intent = repository.record(key, false, 101, "opening", emptyList()) {}
            val sent = checkNotNull(repository.begin(intent, "opening") {})
            failsAtCommit { guard -> repository.settle(sent, KomgaReadStatusPhase.SYNCED, "unread", guard) }
            assertEquals(old, pages.get(key))
            assertTrue(pages.hasUnsettled(key.connectionId, key.ownerKey))
            assertTrue(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "unread") {})
            val superseded = old.copy(supersededByRevision = intent.revision)
            assertEquals(superseded, pages.get(key))
            assertFalse(pages.hasUnsettled(key.connectionId, key.ownerKey))
            assertTrue(pages.pending(key.connectionId, key.ownerKey).isEmpty())
            assertTrue(pages.states(key.connectionId, key.ownerKey, listOf(key.bookId)).getValue(key.bookId).settled)
            assertNull(pages.begin(old, "opening") {})
            pages.holdConnection(key.connectionId)
            assertEquals(superseded, pages.get(key))
            val next = checkNotNull(pages.record(key, intent.revision, 1, 10, 102, "unread") {})
            assertEquals(old.generation + 1, next.generation)
            assertNull(next.supersededByRevision)
            assertTrue(pages.hasUnsettled(key.connectionId, key.ownerKey))
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun deniedAndLegacyCompletionsCannotRetirePageEvents(): Unit = fixture { key ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            val old = checkNotNull(pages.record(key, 0, 3, 10, 100, "opening") {})
            val denied = repository.record(key, false, 101, "opening", emptyList()) {}
            val sent = checkNotNull(repository.begin(denied, "opening") {})
            repository.settle(sent, KomgaReadStatusPhase.DENIED, null) {}
            assertEquals(old, pages.get(key))
            val legacy = repository.record(key, true, 102, "opening", emptyList()) {}
            repository.finish(key, legacy.revision, KomgaReadStatusPhase.SYNCED)
            assertEquals(old, pages.get(key))
            assertTrue(pages.hasUnsettled(key.connectionId, key.ownerKey))
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun oldExplicitConfirmationPreservesNewerPagesAndOtherOwners(): Unit = fixture { key ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            val other = key.copy(ownerKey = "other-owner")
            val otherPage = checkNotNull(pages.record(other, 0, 2, 10, 100, "opening") {})
            val intent = repository.record(key, true, 101, "opening", emptyList()) {}
            val sent = checkNotNull(repository.begin(intent, "opening") {})
            repository.finish(key, intent.revision, KomgaReadStatusPhase.SYNCED)
            val newer = checkNotNull(pages.record(key, intent.revision, 4, 10, 102, "opening") {})
            repository.settle(sent, KomgaReadStatusPhase.SYNCED, "read") {}
            assertEquals(newer, pages.get(key))
            assertEquals(otherPage, pages.get(other))
            assertTrue(pages.hasUnsettled(key.connectionId, key.ownerKey))
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun existingComicEpubAndAggregateBoundariesRejectUncertainRequests(): Unit = fixture { key ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        val ordering = koharia.komga.progress.KomgaProgressOrdering(repository, pages)
        try {
            val intent = repository.record(key, true, 100, "opening", emptyList()) {}
            val attempt = checkNotNull(repository.begin(intent, "opening") {})
            repository.finish(key, intent.revision, KomgaReadStatusPhase.SYNCED)
            val token = ordering.capture(key)
            assertTrue(
                runCatching {
                    ordering.remote(token, {}) { error("Existing progress path bypassed explicit request") }
                }.exceptionOrNull() is koharia.komga.progress.KomgaProgressOnHold,
            )
            assertTrue(
                runCatching {
                    ordering.legacyAggregate(key.connectionId, key.ownerKey, {}) { error("Aggregate bypassed request") }
                }.exceptionOrNull() is koharia.komga.progress.KomgaProgressOnHold,
            )
            repository.settle(attempt, KomgaReadStatusPhase.SYNCED, "after") {}
            val event = checkNotNull(pages.record(key, intent.revision, 3, 10, 101, "after") {})
            pages.begin(event, "after") {}
            assertTrue(
                runCatching {
                    ordering.remote(token, {}) { error("Existing progress path bypassed page request") }
                }.exceptionOrNull() is koharia.komga.progress.KomgaProgressOnHold,
            )
            assertEquals(7, ordering.coordinate(key, {}) { 7 })
            assertEquals(9, ordering.remote(token, {}, allowPageAttemptRecovery = true) { 9 })
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun pageAndExplicitAttemptsAreMutuallyExclusiveAcrossRevisionChanges(): Unit = fixture { key ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            val event = checkNotNull(pages.record(key, 0, 3, 10, 100, "opening") {})
            val page = checkNotNull(pages.begin(event, "opening") {})
            val unread = repository.record(key, false, 101, "opening", emptyList()) {}
            assertNull(repository.begin(unread, "opening") {})
            failsAtCommit { guard -> pages.settle(page, KomgaReadStatusPhase.SYNCED, "after-page", guard) }
            assertEquals(unread, repository.get(key))
            assertEquals(page, pages.attempt(key))
            assertTrue(pages.settle(page, KomgaReadStatusPhase.SYNCED, "after-page") {})
            val advanced = checkNotNull(repository.get(key))
            assertEquals(unread.copy(baseline = "after-page"), advanced)
            val explicit = checkNotNull(repository.begin(advanced, "after-page") {})
            // Even a legacy completion cannot let a new page bypass an uncertain explicit request.
            repository.finish(key, advanced.revision, KomgaReadStatusPhase.SYNCED)
            val later = checkNotNull(pages.record(key, advanced.revision, 5, 10, 102, "after-page") {})
            assertNull(pages.begin(later, "after-page") {})
            assertEquals(explicit, repository.attempt(key))
        } finally {
            pages.removeConnection(key.connectionId)
        }
    }

    @Test
    fun racingPageAndExplicitStartsCannotCreateTwoUncertainRequests(): Unit = fixture { owner ->
        val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
        try {
            repeat(12) { index ->
                val key = owner.copy(bookId = "race-$index")
                val page = checkNotNull(pages.record(key, 0, 3, 10, 100, "opening") {})
                coroutineScope {
                    val pageStart = async { pages.begin(page, "opening") {} }
                    val explicitStart = async {
                        val intent = repository.record(key, false, 101, "opening", emptyList()) {}
                        repository.begin(intent, "opening") {}
                    }
                    assertEquals(1, listOf(pageStart.await(), explicitStart.await()).count { it != null })
                }
                assertEquals(1, listOf(pages.attempt(key), repository.attempt(key)).count { it != null })
            }
        } finally {
            pages.removeConnection(owner.connectionId)
        }
    }

    @Test
    fun explicitTransitionIsRevisionConditionalAndOwnerChecked(): Unit = fixture { key ->
        val old = repository.record(key, true, 100, "opening", emptyList()) {}
        val sent = checkNotNull(repository.begin(old, "opening") {})
        val newer = repository.record(key, false, 101, "opening", emptyList()) {}
        assertFalse(repository.transition(old, KomgaReadStatusPhase.CONFLICT) {})
        failsAtCommit { guard -> repository.transition(newer, KomgaReadStatusPhase.CONFLICT, guard) }
        assertEquals(newer, repository.get(key))
        assertTrue(repository.transition(newer, KomgaReadStatusPhase.CONFLICT) {})
        assertEquals(sent, repository.attempt(key))
        assertFalse(repository.transition(newer, KomgaReadStatusPhase.DENIED) {})
    }

    @Test
    fun migrationRetainsDesiredIntentsAndUncertainPageRequests(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "komga-attempt-migration-${System.nanoTime()}.db"
        check(!context.getDatabasePath(name).exists())
        val previous = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
            override val version = 29L
            override fun create(driver: SqlDriver) = QueryResult.AsyncValue<Unit> {
                Database.Schema.create(driver).await()
                for (table in listOf("komga_page_event", "komga_page_attempt", "komga_read_status")) {
                    driver.execute(null, "DROP INDEX ${table}_book", 0).await()
                }
                driver.execute(null, "DROP TABLE komga_read_status_attempt", 0).await()
                driver.execute(null, "ALTER TABLE komga_page_event DROP COLUMN superseded_by_revision", 0).await()
            }
            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion,
            ) = QueryResult.AsyncValue<Unit> { error("Fixture must start fresh") }
        }
        fun open(schema: SqlSchema<QueryResult.AsyncValue<Unit>>) = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = schema,
        )
        try {
            open(previous).use { driver ->
                for (owner in listOf("first", "second")) {
                    driver.execute(
                        null,
                        "INSERT INTO komga_page_event VALUES (1,'$owner','book',3,1,4,10,100,'opening','HELD')",
                        0,
                    ).await()
                    driver.execute(
                        null,
                        "INSERT INTO komga_read_status VALUES (1,'$owner','book',2,0,100,'opening','PENDING')",
                        0,
                    ).await()
                    driver.execute(
                        null,
                        "INSERT INTO komga_page_attempt VALUES (1,'$owner','book',3,1,4,10,100,'opening','before')",
                        0,
                    ).await()
                }
            }
            open(Database.Schema).use { driver ->
                suspend fun values(sql: String) = driver.executeQuery(null, sql, { cursor ->
                    QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)) })
                }, 0).await()
                for (table in listOf(
                    "komga_page_event",
                    "komga_page_attempt",
                    "komga_read_status",
                    "komga_read_status_attempt",
                )) {
                    val plan = driver.executeQuery(
                        null,
                        "EXPLAIN QUERY PLAN SELECT book_id FROM $table WHERE connection_id=1 AND book_id IN ('book')",
                        { cursor ->
                            QueryResult.Value(
                                buildList {
                                    while (cursor.next().value) add(cursor.getString(3).orEmpty())
                                },
                            )
                        },
                        0,
                    ).await()
                    assertTrue(plan.toString(), plan.any { it.contains("${table}_book") })
                }
                assertEquals(
                    listOf("first:2:0:opening", "second:2:0:opening"),
                    values(
                        "SELECT owner_key || ':' || revision || ':' || read || ':' || baseline " +
                            "FROM komga_read_status ORDER BY owner_key",
                    ),
                )
                assertEquals(
                    listOf("first:3:before", "second:3:before"),
                    values(
                        "SELECT owner_key || ':' || generation || ':' || baseline FROM komga_page_attempt ORDER BY owner_key",
                    ),
                )
                assertTrue(values("SELECT book_id FROM komga_read_status_attempt").isEmpty())
                assertEquals(
                    listOf("first:3:HELD:unresolved", "second:3:HELD:unresolved"),
                    values(
                        "SELECT owner_key || ':' || generation || ':' || phase || ':' || " +
                            "COALESCE(superseded_by_revision, 'unresolved') FROM komga_page_event ORDER BY owner_key",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun uncertainRequestSurvivesReplacementAndCannotAcknowledgeNewerIntent(): Unit = fixture { key ->
        val first = repository.record(key, true, 100, "opening", emptyList()) {}
        val sent = checkNotNull(repository.begin(first, "opening") {})
        val newer = repository.record(key, false, 99, "opening", emptyList()) {}
        assertEquals(sent, repository.attempt(key))
        assertNull(repository.begin(newer, "opening") {})
        assertTrue(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "after-first") {})
        assertEquals(newer.copy(baseline = "after-first"), repository.get(key))
        assertFalse(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "late") {})
        val latest = checkNotNull(repository.get(key))
        val next = checkNotNull(repository.begin(latest, "after-first") {})
        assertTrue(repository.settle(next, KomgaReadStatusPhase.SYNCED, "unread") {})
        assertEquals(KomgaReadStatusPhase.SYNCED, repository.get(key)?.phase)
    }

    @Test
    fun unknownOrIndependentlyAcceptedNewBaselineCannotInheritOldConfirmation(): Unit = fixture { key ->
        for (baseline in listOf(null, "independently-accepted")) {
            val first = repository.record(key, true, 100, "opening", emptyList()) {}
            val sent = checkNotNull(repository.begin(first, "opening") {})
            val newer = repository.record(key, false, 101, baseline, emptyList()) {}
            repository.settle(sent, KomgaReadStatusPhase.SYNCED, "old-confirmation") {}
            assertEquals(newer, repository.get(key))
        }
    }

    @Test
    fun concurrentDispatchBeginsOnceAndOwnerChangesRollBackBothEdges(): Unit = fixture { key ->
        val intent = repository.record(key, false, 100, "opening", emptyList()) {}
        failsAtCommit { guard -> repository.begin(intent, "opening", guard) }
        assertNull(repository.attempt(key))
        val attempts = coroutineScope {
            (1..8).map { async { repository.begin(intent, "opening") {} } }.awaitAll()
        }
        assertEquals(1, attempts.count { it != null })
        val sent = attempts.filterNotNull().single()
        failsAtCommit { guard -> repository.settle(sent, KomgaReadStatusPhase.SYNCED, "after", guard) }
        assertEquals(intent, repository.get(key))
        assertEquals(sent, repository.attempt(key))
    }

    @Test
    fun holdAndAccountIsolationPreserveUncertainAttemptsUntilOwnedRemoval(): Unit = fixture { key ->
        val other = key.copy(ownerKey = "different-owner")
        val foreign = key.copy(connectionId = key.connectionId - 1)
        try {
            val sent = listOf(key, other, foreign).map {
                val intent = repository.record(it, true, 100, "opening", emptyList()) {}
                checkNotNull(repository.begin(intent, "opening") {})
            }
            assertEquals(listOf(sent.first()), repository.attempts(key.connectionId, key.ownerKey))
            repository.holdConnection(key.connectionId)
            assertEquals(sent.first(), repository.attempt(key))
            assertTrue(repository.settle(sent.first(), KomgaReadStatusPhase.SYNCED, "after") {})
            assertEquals(KomgaReadStatusPhase.HELD, repository.get(key)?.phase)
            repository.removeConnection(key.connectionId)
            assertNull(repository.attempt(other))
            assertEquals(sent.last(), repository.attempt(foreign))
        } finally {
            repository.removeConnection(foreign.connectionId)
        }
    }

    private suspend fun failsAtCommit(block: suspend (() -> Unit) -> Any?) {
        var calls = 0
        val failure = IllegalStateException("fixture owner changed")
        assertEquals(failure, runCatching { block { if (++calls == 2) throw failure } }.exceptionOrNull())
        assertEquals(2, calls)
    }

    private fun fixture(block: suspend (KomgaReadStatusKey) -> Unit): Unit = runBlocking(Dispatchers.IO) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val key = KomgaReadStatusKey(System.nanoTime(), "read-attempt-fixture", "book")
        try {
            block(key)
        } finally {
            repository.removeConnection(key.connectionId)
        }
    }
}
