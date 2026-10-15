package koharia.komga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.data.komga.KomgaPageEventRepositoryImpl
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaProgressSyncStatus
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KomgaPageEventRepositoryDeviceTest {
    private val database get() = Injekt.get<Database>()
    private val repository get() = KomgaPageEventRepositoryImpl(database)
    private val intents get() = Injekt.get<KomgaReadStatusRepository>()

    @Test
    fun syncStatusObservesBothJournalsAndSeparatesOwners(): Unit = fixture { key ->
        val updates = Channel<Map<String, Set<KomgaProgressSyncStatus>>>(Channel.UNLIMITED)
        kotlinx.coroutines.coroutineScope {
            val watch = launch {
                repository.observeSyncStatus(key.connectionId, key.ownerKey, listOf(key.bookId)).collect {
                    updates.send(it)
                }
            }
            suspend fun expect(vararg status: KomgaProgressSyncStatus) = withTimeout(5_000) {
                val expected = if (status.isEmpty()) emptyMap() else mapOf(key.bookId to status.toSet())
                while (updates.receive() != expected) Unit
            }
            try {
                expect()
                val page = record(key, 2)
                expect(KomgaProgressSyncStatus.PENDING)
                val attempt = checkNotNull(repository.begin(page, "opening-baseline") {})
                expect(KomgaProgressSyncStatus.VERIFYING)
                repository.transition(page, KomgaReadStatusPhase.DENIED) {}
                expect(KomgaProgressSyncStatus.DENIED)
                record(key.copy(ownerKey = "restored:fixture"), 3)
                expect(KomgaProgressSyncStatus.DENIED, KomgaProgressSyncStatus.UNVERIFIED_OWNER)
                val intent = intents.record(key, false, 101, "opening-baseline", emptyList()) {}
                expect(KomgaProgressSyncStatus.VERIFYING, KomgaProgressSyncStatus.UNVERIFIED_OWNER)
                intents.transition(intent, KomgaReadStatusPhase.HELD) {}
                expect(KomgaProgressSyncStatus.HELD, KomgaProgressSyncStatus.UNVERIFIED_OWNER)
                assertEquals(attempt, repository.attempt(key))
            } finally {
                watch.cancelAndJoin()
                updates.close()
            }
        }
    }

    @Test
    fun syncStatusBatchesRequestedBooksAndOmitsSupersededPages(): Unit = fixture { key ->
        val keys = (0..450).map { key.copy(bookId = "book-$it") }
        database.transaction { keys.forEach { record(it, 2) } }
        record(key.copy(bookId = "unrequested"), 4)
        val statuses = repository.observeSyncStatus(key.connectionId, key.ownerKey, keys.map { it.bookId }).first()
        assertEquals(keys.size, statuses.size)
        assertTrue(statuses.values.all { it == setOf(KomgaProgressSyncStatus.PENDING) })
        val intent = intents.record(keys.first(), false, 101, "opening-baseline", emptyList()) {}
        val request = checkNotNull(intents.begin(intent, "opening-baseline") {})
        assertTrue(intents.settle(request, KomgaReadStatusPhase.SYNCED, "confirmed") {})
        val settled = repository.observeSyncStatus(key.connectionId, key.ownerKey, listOf(keys.first().bookId)).first()
        assertTrue(settled.isEmpty())
        assertTrue(
            repository.observeSyncStatus(
                key.connectionId + 1,
                key.ownerKey,
                keys.map {
                    it.bookId
                },
            ).first().isEmpty(),
        )
    }

    @Test
    fun processRecovery(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val phase = InstrumentationRegistry.getArguments().getString("komgaPageEventPhase")
        assumeTrue(phase in setOf("prepare", "interrupt", "recover"))
        val checkpoint = context.getSharedPreferences("komga-page-event-checkpoint", 0)
        val ready = File(context.cacheDir, "komga-page-event-interrupt-ready")
        if (phase == "recover") {
            val source = checkpoint.getLong("source", -1)
            check(source > 0)
            val key = KomgaReadStatusKey(source, "page-event-restart-fixture", "book")
            try {
                val event = checkNotNull(repository.get(key))
                val attempt = checkNotNull(repository.attempt(key))
                assertEquals(2L, event.generation)
                assertEquals(5L, event.page)
                assertEquals(KomgaReadStatusPhase.PENDING, event.phase)
                assertEquals(1L, attempt.event.generation)
                assertEquals(2L, attempt.event.page)
                assertEquals("before-request", attempt.baseline)
                assertNull(repository.begin(event, "must-recover-first") {})
                assertTrue(repository.settle(attempt, KomgaReadStatusPhase.SYNCED, "recovered-baseline") {})
                assertEquals(2L, repository.get(key)?.generation)
                assertEquals(KomgaReadStatusPhase.PENDING, repository.get(key)?.phase)
            } finally {
                repository.removeConnection(source)
                context.deleteSharedPreferences("komga-page-event-checkpoint")
                ready.delete()
            }
            return@runBlocking
        }
        check(checkpoint.all.isEmpty()) { "Recover the previous fixture checkpoint first" }
        check(!ready.exists())
        val source = System.nanoTime()
        val key = KomgaReadStatusKey(source, "page-event-restart-fixture", "book")
        check(checkpoint.edit().putLong("source", source).commit())
        val event = record(key, 2)
        repository.begin(event, "before-request") {}
        record(key, 5)
        if (phase == "interrupt") {
            var checks = 0
            repository.record(key, 0, 8, 10, 101, null) {
                if (++checks == 2) {
                    ready.writeText("uncommitted")
                    CountDownLatch(1).await(45, TimeUnit.SECONDS)
                    error("Host did not interrupt the fixture transaction")
                }
            }
        }
    }

    @Test
    fun lateAcknowledgementPreservesNewerEventAndTransfersConfirmedBaseline(): Unit = fixture { key ->
        val first = record(key, 2)
        val sent = checkNotNull(repository.begin(first, "server-before") {})
        val newer = record(key, 5)
        assertNull(repository.begin(newer, "server-before") {})
        // Recreate the repository: neither the event nor the unresolved request belongs to a reader instance.
        assertEquals(newer, repository.get(key))
        assertEquals(sent, repository.attempt(key))
        assertTrue(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "server-after-first") {})
        val pending = checkNotNull(repository.get(key))
        assertEquals(newer.generation, pending.generation)
        assertEquals(5L, pending.page)
        assertEquals("server-after-first", pending.baseline)
        assertEquals(KomgaReadStatusPhase.PENDING, pending.phase)
        assertFalse(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "stale-response") {})
        assertEquals(pending, repository.get(key))
        assertNull(repository.begin(newer, "stale-baseline") {})
        val latestAttempt = checkNotNull(repository.begin(pending, "server-after-first") {})
        assertTrue(repository.settle(latestAttempt, KomgaReadStatusPhase.SYNCED, "server-after-newer") {})
        assertEquals(KomgaReadStatusPhase.SYNCED, repository.get(key)?.phase)
        assertTrue(repository.pending(key.connectionId, key.ownerKey).isEmpty())
        assertFalse(repository.hasUnsettled(key.connectionId, key.ownerKey))
        assertEquals(newer.generation + 1, record(key, 6).generation)
    }

    @Test
    fun newReadingCannotReplaceUnknownOrConfirmedBaselineWithoutComparison(): Unit = fixture { key ->
        val offline = checkNotNull(repository.record(key, 0, 1, 10, 100, null) {})
        val later = checkNotNull(repository.record(key, 0, 2, 10, 101, "unconfirmed-remote-change") {})
        assertNull(offline.baseline)
        assertNull(later.baseline)
        val attempt = checkNotNull(repository.begin(later, "validated-baseline") {})
        repository.settle(attempt, KomgaReadStatusPhase.SYNCED, "confirmed-result") {}
        val accepted = checkNotNull(repository.record(key, 0, 3, 10, 102, "accepted-new-opening") {})
        assertEquals("confirmed-result", accepted.baseline)
        assertEquals(3L, accepted.generation)
        val compared = checkNotNull(
            repository.reconcileLocal(
                key,
                0,
                koharia.domain.komga.KomgaPageState(accepted, null),
                3,
                10,
                103,
                "accepted-new-opening",
                true,
            ) {},
        )
        assertEquals("accepted-new-opening", compared.baseline)
        assertEquals(4L, compared.generation)
        assertNull(
            repository.reconcileLocal(
                key,
                0,
                koharia.domain.komga.KomgaPageState(accepted, null),
                1,
                10,
                104,
                "stale-comparison",
                true,
            ) {},
        )
    }

    @Test
    fun concurrentEventsHaveUniqueDurableGenerationsAndOnlyLatestCanBegin(): Unit = fixture { key ->
        val events = coroutineScope {
            (0L..15L).map { page -> async { record(key, page, total = 20) } }.awaitAll()
        }
        assertEquals((1L..16L).toSet(), events.map { it.generation }.toSet())
        val latest = events.maxBy { it.generation }
        assertEquals(latest, repository.get(key))
        events.filter { it != latest }.forEach { assertNull(repository.begin(it, "baseline") {}) }
        val attempts = coroutineScope {
            (1..8).map { async { repository.begin(latest, "baseline") {} } }.awaitAll()
        }
        assertEquals(1, attempts.count { it != null })
        assertEquals(latest, repository.attempt(key)?.event)
    }

    @Test
    fun explicitResetAndConnectionHoldDoNotAdoptOldAttempt(): Unit = fixture { key ->
        val event = record(key, 2)
        val sent = checkNotNull(repository.begin(event, "before-reset") {})
        val reset = intents.record(key, false, 200, null, emptyList()) {}
        assertNull(repository.record(key, 0, 3, 10, 201, null) {})
        assertNull(repository.record(key, reset.revision, 3, 10, 201, null) {})
        assertTrue(intents.finish(key, reset.revision, KomgaReadStatusPhase.SYNCED))
        val fresh = checkNotNull(repository.record(key, reset.revision, 1, 10, 202, "after-reset") {})
        assertNull(repository.begin(fresh, "after-reset") {})
        assertTrue(repository.settle(sent, KomgaReadStatusPhase.SYNCED, "old-reply") {})
        assertEquals(fresh, repository.get(key))
        val freshAttempt = checkNotNull(repository.begin(fresh, "after-reset") {})
        repository.holdConnection(key.connectionId)
        assertEquals(freshAttempt, repository.attempt(key))
        assertTrue(repository.pending(key.connectionId, key.ownerKey).isEmpty())
        assertTrue(repository.hasUnsettled(key.connectionId, key.ownerKey))
        assertFalse(repository.hasUnsettled(key.connectionId, "unrelated-owner"))
        assertTrue(repository.settle(freshAttempt, KomgaReadStatusPhase.SYNCED, "late-reply") {})
        assertEquals(KomgaReadStatusPhase.HELD, repository.get(key)?.phase)
        assertEquals("after-reset", repository.get(key)?.baseline)
    }

    @Test
    fun ownerFailuresRollBackEventAttemptAndAcknowledgement(): Unit = fixture { key ->
        val event = record(key, 2)
        failsAtCommit { guard -> repository.record(key, 0, 4, 10, 101, null, guard) }
        assertEquals(event, repository.get(key))
        failsAtCommit { guard -> repository.begin(event, "baseline", guard) }
        assertNull(repository.attempt(key))
        val sent = checkNotNull(repository.begin(event, "baseline") {})
        failsAtCommit { guard -> repository.settle(sent, KomgaReadStatusPhase.SYNCED, "after", guard) }
        assertEquals(sent, repository.attempt(key))
        assertEquals(event, repository.get(key))
    }

    @Test
    fun accountAndConnectionQueriesRemainIsolated(): Unit = fixture { key ->
        val otherAccount = key.copy(ownerKey = "other")
        val otherConnection = key.copy(connectionId = key.connectionId - 1)
        try {
            val own = record(key, 2)
            val account = record(otherAccount, 3)
            val connection = record(otherConnection, 4)
            repository.begin(own, "own") {}
            repository.begin(account, "account") {}
            repository.begin(connection, "connection") {}
            assertEquals(listOf(own), repository.pending(key.connectionId, key.ownerKey))
            repository.removeConnection(key.connectionId)
            assertNull(repository.get(key))
            assertNull(repository.get(otherAccount))
            assertNull(repository.attempt(key))
            assertNull(repository.attempt(otherAccount))
            assertEquals(connection, repository.get(otherConnection))
            assertEquals(connection, repository.attempt(otherConnection)?.event)
        } finally {
            repository.removeConnection(otherConnection.connectionId)
        }
    }

    @Test
    fun conflictRetainsNewerPagesButRequiresDeliberateReissue(): Unit = fixture { key ->
        val old = record(key, 2)
        val sent = checkNotNull(repository.begin(old, "baseline") {})
        val newer = record(key, 4)
        assertTrue(repository.settle(sent, KomgaReadStatusPhase.CONFLICT, null) {})
        assertEquals(newer.copy(phase = KomgaReadStatusPhase.CONFLICT), repository.get(key))
        assertNull(repository.begin(newer, "changed-server") {})
        val continued = record(key, 5)
        assertEquals(KomgaReadStatusPhase.CONFLICT, continued.phase)
        assertEquals(5L, continued.page)
        assertNull(repository.begin(continued, "changed-server") {})
        assertNull(repository.attempt(key))
        assertTrue(repository.hasUnsettled(key.connectionId, key.ownerKey))
    }

    @Test
    fun transitionsRetainAmbiguousRequestsAndRollBackOnOwnerChange(): Unit = fixture { key ->
        val old = record(key, 2)
        val sent = checkNotNull(repository.begin(old, "baseline") {})
        val newer = record(key, 4)
        assertFalse(repository.transition(old, KomgaReadStatusPhase.CONFLICT) {})
        failsAtCommit { guard -> repository.transition(newer, KomgaReadStatusPhase.CONFLICT, guard) }
        assertEquals(newer, repository.get(key))
        for (phase in listOf(KomgaReadStatusPhase.DENIED, KomgaReadStatusPhase.HELD)) {
            val current = checkNotNull(repository.get(key))
            assertTrue(repository.transition(current, phase) {})
            assertEquals(sent, repository.attempt(key))
            assertEquals(phase, record(key, 6).phase)
        }
    }

    private suspend fun record(key: KomgaReadStatusKey, page: Long, total: Long = 10): KomgaPageEvent =
        checkNotNull(repository.record(key, 0, page, total, 100, "opening-baseline") {})

    private suspend fun failsAtCommit(operation: suspend (() -> Unit) -> Any?) {
        var calls = 0
        val failure = IllegalStateException("fixture owner changed")
        val result = runCatching { operation { if (++calls == 2) throw failure } }
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(2, calls)
    }

    private fun fixture(block: suspend (KomgaReadStatusKey) -> Unit) = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val key = KomgaReadStatusKey(System.nanoTime(), "page-event-fixture", "book")
        try {
            block(key)
        } finally {
            repository.removeConnection(key.connectionId)
            intents.removeConnection(key.connectionId)
        }
    }
}
