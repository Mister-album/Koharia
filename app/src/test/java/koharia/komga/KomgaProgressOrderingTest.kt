package koharia.komga

import io.mockk.coEvery
import io.mockk.mockk
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaProgressOnHold
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaProgressSuperseded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaProgressOrderingTest {
    private val key = KomgaReadStatusKey(1, "owner", "book")
    private val rows = mutableMapOf<KomgaReadStatusKey, KomgaReadStatusIntent>()
    private val repository = mockk<KomgaReadStatusRepository> {
        coEvery { this@mockk.get(any()) } coAnswers { rows[firstArg()] }
        coEvery { forOwner(any(), any()) } coAnswers {
            rows.values.filter { it.key.connectionId == firstArg<Long>() && it.key.ownerKey == secondArg<String>() }
        }
        coEvery { record(any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(5).invoke()
            val owner = firstArg<KomgaReadStatusKey>()
            KomgaReadStatusIntent(
                owner,
                (rows[owner]?.revision ?: 0) + 1,
                secondArg(),
                thirdArg(),
                null,
                KomgaReadStatusPhase.PENDING,
            )
                .also { rows[owner] = it }
        }
        coEvery { finish(any(), any(), any()) } coAnswers {
            val owner = firstArg<KomgaReadStatusKey>()
            val row = rows[owner]
            if (row?.revision == secondArg<Long>() && row.phase == KomgaReadStatusPhase.PENDING) {
                rows[owner] = row.copy(phase = thirdArg())
                true
            } else {
                false
            }
        }
    }
    private val ordering = testProgressOrdering(repository)

    @Test
    fun `legacy aggregate waits for old book requests and excludes new book requests until response`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<Unit>()
        val aggregateEntered = CompletableDeferred<Unit>()
        val aggregateResponse = CompletableDeferred<Unit>()
        val first = async {
            ordering.remote(ordering.capture(key), {}) {
                entered.complete(Unit)
                firstResponse.await()
            }
        }
        entered.await()
        val aggregate = async {
            ordering.legacyAggregate(1, "owner", {}) {
                aggregateEntered.complete(Unit)
                aggregateResponse.await()
            }
        }
        runCurrent()
        assertFalse(aggregateEntered.isCompleted)
        firstResponse.complete(Unit)
        first.await()
        aggregateEntered.await()
        var sent = false
        val later = async {
            ordering.remote(ordering.capture(key.copy(bookId = "other")), {}) { sent = true }
        }
        runCurrent()
        assertFalse(sent)
        aggregateResponse.complete(Unit)
        aggregate.await()
        later.await()
        assertTrue(sent)
    }

    @Test
    fun `cancelled aggregate releases partial acquisition and unsettled owner cannot send`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val first = async {
            ordering.remote(ordering.capture(key), {}) {
                entered.complete(Unit)
                response.await()
            }
        }
        entered.await()
        val aggregate = async { ordering.legacyAggregate(1, "owner", {}) { error("Must wait") } }
        runCurrent()
        aggregate.cancelAndJoin()
        response.complete(Unit)
        first.await()
        assertEquals(7, ordering.legacyAggregate(1, "owner", {}) { 7 })
        ordering.record(key, false, 1, null, emptyList()) {}
        assertTrue(
            runCatching { ordering.legacyAggregate(1, "owner", {}) { error("Pending intent bypassed") } }
                .exceptionOrNull() is KomgaProgressOnHold,
        )
        assertEquals(8, ordering.legacyAggregate(1, "other-owner", {}) { 8 })
    }

    @Test
    fun `explicit reset invalidates a reader even when both local progress snapshots are absent`() = runTest {
        val opened = ordering.capture(key)
        ordering.record(key, false, 1, null, emptyList()) {}
        assertTrue(runCatching { ordering.checkCurrent(opened) }.exceptionOrNull() is KomgaProgressSuperseded)
        val reopened = ordering.capture(key)
        ordering.checkCurrent(reopened)
        assertTrue(
            runCatching {
                ordering.remote(reopened, {}) { error("Must not send") }
            }.exceptionOrNull() is KomgaProgressOnHold,
        )
        rows[key] = rows.getValue(key).copy(phase = KomgaReadStatusPhase.SYNCED)
        assertEquals("sent", ordering.remote(reopened, {}) { "sent" })
    }

    @Test
    fun `intent commits immediately while remote dispatch waits for the older request`() = runTest {
        val opened = ordering.capture(key)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val request = async {
            runCatching {
                ordering.remote(opened, {}) {
                    entered.complete(Unit)
                    response.await()
                }
            }
        }
        entered.await()
        val reset = ordering.record(key, false, 1, null, emptyList()) {}
        assertEquals(1L, reset.revision)
        var dispatched = false
        val send = async {
            ordering.dispatch(reset, {}) {
                dispatched = true
                KomgaReadStatusPhase.SYNCED
            }
        }
        runCurrent()
        assertFalse(send.isCompleted)
        assertFalse(dispatched)
        response.complete(Unit)
        assertTrue(request.await().exceptionOrNull() is KomgaProgressSuperseded)
        assertTrue(send.await())
        assertTrue(
            runCatching {
                ordering.remote(opened, {}) { error("Obsolete request sent") }
            }.exceptionOrNull() is KomgaProgressSuperseded,
        )
    }

    @Test
    fun `late dispatch acknowledgement leaves a newer explicit intent pending`() = runTest {
        val first = ordering.record(key, true, 1, null, emptyList()) {}
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val send = async {
            ordering.dispatch(first, {}) {
                entered.complete(Unit)
                response.await()
                KomgaReadStatusPhase.SYNCED
            }
        }
        entered.await()
        val next = ordering.record(key, false, 2, null, emptyList()) {}
        response.complete(Unit)
        assertFalse(send.await())
        assertEquals(next, rows[key])
        assertFalse(ordering.dispatch(first, {}) { error("Superseded intent dispatched") })
    }

    @Test
    fun `other owners retain their revisions and owner validation happens before requests`() = runTest {
        val other = ordering.capture(key.copy(ownerKey = "other"))
        ordering.record(key, true, 1, null, emptyList()) {}
        ordering.checkCurrent(other)
        var sent = false
        val failure = runCatching {
            ordering.remote(other, { throw IllegalStateException("Owner removed") }) { sent = true }
        }
        assertTrue(failure.isFailure)
        assertFalse(sent)
        assertEquals(3, ordering.remote(other, {}) { 3 })
    }

    @Test
    fun `book keys retain connection and account while excluding endpoint and presentation fragment`() {
        assertEquals(key, KomgaProgressOrdering.key(1, "owner", "https://example.test/komga/api/v1/books/book/#page"))
        assertEquals(key, KomgaProgressOrdering.key(1, "owner", "http://internal.test/api/v1/books/book"))
        for (invalid in listOf(
            "https://example.test/api/v1/series/book",
            "https://example.test/api/v1/books/book/pages",
        )) {
            assertTrue(runCatching { KomgaProgressOrdering.key(1, "owner", invalid) }.isFailure)
        }
    }
}
