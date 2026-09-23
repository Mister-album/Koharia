package koharia.smanga

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import koharia.connection.ConnectionRestoreState
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SmangaReadingCoordinatorTest {
    private val chapter = SmangaChapter(10, 20, 30, "Chapter", pageCount = 10)
    private val memo = JsonObject(emptyMap())

    @Test
    fun `remote unread deletion resets acknowledged chapters without creating visits or new states`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100).copy(completed = true, pageIndex = 9)
        fixture.states[11] = local(11, 100)
        fixture.states[12] = local(12, 100).copy(pending = true)
        fixture.states[13] = local(13, 100)
        fixture.coordinator.requireMappingConfirmation(13)
        coEvery { fixture.api.chapters(20) } returns (10L..14).map { chapter.copy(id = it, latest = null) }

        fixture.coordinator.syncManga(20)
        fixture.coordinator.syncManga(20)

        for (id in listOf(10L, 11L)) {
            val reset = fixture.states.getValue(id)
            assertFalse(reset.completed)
            assertFalse(reset.pending)
            assertTrue(reset.explicitUnread)
            assertEquals(-1, reset.pageIndex)
            assertEquals(0L, reset.readAt)
        }
        assertTrue(fixture.states.getValue(12).pending)
        assertEquals(local(13, 100), fixture.states.getValue(13))
        assertFalse(fixture.states.containsKey(14))
        assertEquals(listOf(10L, 11L), fixture.applied.map { it.chapterId })
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reader pull applies remote unread even when the old local timestamp is newer`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100).copy(completed = true, pageIndex = 9)

        val snapshot = fixture.coordinator.pull(chapter, memo)!!

        assertFalse(snapshot.completed)
        assertNull(snapshot.pageIndex)
        assertFalse(snapshot.requiresConfirmation)
        assertTrue(fixture.states.getValue(10).explicitUnread)
        assertFalse(fixture.states.getValue(10).completed)
        assertTrue(fixture.history.isEmpty())
        fixture.coordinator.accept(chapter, 0, 10, 0)
        fixture.coordinator.beginSession(10)
        fixture.coordinator.record(chapter, 0, 10, 200, true)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 0, 10, false) }
        assertEquals(1, fixture.history.size)
    }

    @Test
    fun `remote unread pull preserves pending local progress and mapping confirmation`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100).copy(pending = true)
        val pending = fixture.states.getValue(10)

        assertEquals(2, fixture.coordinator.pull(chapter, memo)!!.pageIndex)
        assertEquals(pending, fixture.states.getValue(10))
        fixture.coordinator.requireMappingConfirmation(10)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        assertEquals(pending, fixture.states.getValue(10))
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun `late remote unread response cannot erase a newer acknowledged upload`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { fixture.api.chapters(20) } coAnswers {
            if (calls++ == 0) {
                entered.complete(Unit)
                release.await()
            }
            listOf(chapter)
        }
        val sync = async { fixture.coordinator.syncManga(20) }
        entered.await()
        fixture.coordinator.record(chapter, 5, 10, 200, false)
        advanceTimeBy(1000)
        runCurrent()
        val uploaded = fixture.states.getValue(10)
        assertFalse(uploaded.pending)
        release.complete(Unit)
        sync.await()

        assertEquals(uploaded, fixture.states.getValue(10))
        assertEquals(5, uploaded.pageIndex)
    }

    @Test
    fun `late unread sync cannot replace a newer remote snapshot offered by the reader`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { fixture.api.chapters(20) } coAnswers {
            if (calls++ == 0) {
                entered.complete(Unit)
                release.await()
                listOf(chapter)
            } else {
                listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
            }
        }
        val sync = async { fixture.coordinator.syncManga(20) }
        entered.await()
        assertEquals(7, fixture.coordinator.pull(chapter, memo)!!.pageIndex)
        release.complete(Unit)
        sync.await()

        assertEquals(local(10, 100), fixture.states.getValue(10))
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun `offline reading stays pending and reconnect retries after bounded failures`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } throws IOException("Offline")

        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertEquals(0, fixture.history.values.single().status)
        coVerify(exactly = 3) { fixture.api.chapters(20) }
        coVerify(exactly = 0) { fixture.api.addHistory(any(), any(), any()) }
        coEvery { fixture.api.chapters(20) } returns listOf(chapter)
        fixture.coordinator.retryPending()
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        assertEquals(2, fixture.history.values.single().status)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 1) { fixture.api.addHistory(30, 20, 10) }
        coVerify(exactly = 0) { fixture.repository.readStates(any(), any()) }
        coVerify(exactly = 0) { fixture.repository.historyEvents(any(), any()) }
    }

    @Test
    fun `newer remote conflict retains local pending until the reader accepts`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()
        val pending = fixture.states.getValue(10)

        val offered = fixture.coordinator.pull(chapter, memo)!!

        assertTrue(offered.requiresConfirmation)
        assertEquals(7, offered.pageIndex)
        assertEquals(pending, fixture.states.getValue(10))
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
        fixture.coordinator.accept(chapter, 7, 10, 200)
        fixture.coordinator.retryPending()
        advanceUntilIdle()
        assertEquals(7, fixture.states.getValue(10).pageIndex)
        assertFalse(fixture.states.getValue(10).pending)
        assertEquals(1, fixture.history.size)
        coVerify(exactly = 1) { fixture.api.addHistory(30, 20, 10) }
    }

    @Test
    fun `initial display cannot overwrite a different saved remote position`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 50)))

        fixture.coordinator.record(chapter, 0, 10, 100, true)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `local navigation persists while a chapter fetch is in flight`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var fetches = 0
        coEvery { fixture.api.chapters(20) } coAnswers {
            if (fetches++ == 0) {
                entered.complete(Unit)
                response.await()
            }
            listOf(chapter)
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceTimeBy(1000)
        runCurrent()
        entered.await()

        fixture.coordinator.record(chapter, 6, 10, 200, false)
        assertEquals(6, fixture.states.getValue(10).pageIndex)
        response.complete(Unit)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 6, 10, false) }
        assertEquals(1, fixture.history.size)
    }

    @Test
    fun `old upload completion cannot acknowledge a newer reading revision`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { fixture.api.pushProgress(20, 10, 2, 10, false) } coAnswers {
            entered.complete(Unit)
            response.await()
            SmangaProgress(2, 10, false)
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        val firstRevision = fixture.states.getValue(10).revision
        advanceTimeBy(1000)
        runCurrent()
        entered.await()

        fixture.coordinator.record(chapter, 6, 10, 200, false)
        val secondRevision = fixture.states.getValue(10).revision
        response.complete(Unit)
        advanceUntilIdle()

        assertTrue(secondRevision > firstRevision)
        assertEquals(listOf(secondRevision), fixture.acknowledged)
        assertEquals(6, fixture.states.getValue(10).pageIndex)
        assertFalse(fixture.states.getValue(10).pending)
    }

    @Test
    fun `a delayed pull cannot replace the baseline acknowledged by a newer write`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var delayNextFetch = true
        var remote = SmangaProgress(2, 10, false, 50)
        coEvery { fixture.api.chapters(20) } coAnswers {
            val captured = chapter.copy(latest = remote)
            if (delayNextFetch) {
                delayNextFetch = false
                entered.complete(Unit)
                response.await()
            }
            listOf(captured)
        }
        coEvery { fixture.api.pushProgress(20, 10, 9, 10, true) } coAnswers {
            SmangaProgress(9, 10, true, 200).also { remote = it }
        }
        coEvery { fixture.api.markUnread(20, 10, 10) } coAnswers {
            SmangaProgress(-1, 10, false, 300).also { remote = it }
        }
        fixture.coordinator.record(chapter, 9, 10, 100, false)
        val pull = async { fixture.coordinator.pull(chapter, memo) }
        runCurrent()
        entered.await()

        advanceUntilIdle()
        fixture.assertAcknowledged(9, 10, completed = true)
        assertEquals(SmangaProgress(9, 10, true, 200), remote)
        response.complete(Unit)
        val snapshot = checkNotNull(pull.await())
        assertEquals(9, snapshot.pageIndex)
        assertTrue(snapshot.completed)
        assertFalse(snapshot.requiresConfirmation)

        fixture.coordinator.setRead(chapter, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(0, 10, completed = false, explicitUnread = true)
        assertEquals(SmangaProgress(-1, 10, false, 300), remote)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 9, 10, true) }
        coVerify(exactly = 1) { fixture.api.markUnread(20, 10, 10) }
    }

    @Test
    fun `concurrent pulls preserve the remote choice already offered by the first conflict`() = runTest {
        val fixture = Fixture(this)
        val responses = List(2) { CompletableDeferred<Unit>() }
        val remote = chapter.copy(latest = SmangaProgress(5, 10, false, 200))
        var fetches = 0
        coEvery { fixture.api.chapters(20) } coAnswers {
            val index = fetches++
            responses.getOrNull(index)?.await()
            listOf(remote)
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        val first = async { fixture.coordinator.pull(chapter, memo) }
        val second = async { fixture.coordinator.pull(chapter, memo) }
        runCurrent()
        assertEquals(2, fetches)

        responses[0].complete(Unit)
        val firstOffer = checkNotNull(first.await())
        assertEquals(5, firstOffer.pageIndex)
        assertTrue(firstOffer.requiresConfirmation)
        responses[1].complete(Unit)
        val secondOffer = checkNotNull(second.await())
        assertEquals(5, secondOffer.pageIndex)
        assertTrue(secondOffer.requiresConfirmation)
        assertEquals(2, fixture.states.getValue(10).pageIndex)
        assertTrue(fixture.states.getValue(10).pending)

        fixture.coordinator.accept(chapter, checkNotNull(secondOffer.pageIndex), secondOffer.totalPages, 200)
        advanceUntilIdle()
        fixture.assertAcknowledged(5, 10, completed = false)
        assertEquals(200L, fixture.states.getValue(10).readAt)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown history outcome is attempted once and never replayed`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.addHistory(30, 20, 10) } coAnswers {
            assertEquals(1, fixture.history.values.single().status)
            throw IOException("Response lost")
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()
        fixture.coordinator.record(chapter, 3, 10, 200, false)
        fixture.coordinator.retryPending()
        advanceUntilIdle()

        assertEquals(1, fixture.history.values.single().status)
        coVerify(exactly = 1) { fixture.api.addHistory(30, 20, 10) }
    }

    @Test
    fun `first display exit record and subsequent pages share one history event`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.record(chapter, 0, 10, 100, true)
        fixture.coordinator.record(chapter, 0, 10, 150, true)
        fixture.coordinator.record(chapter, 1, 10, 200, false)
        advanceUntilIdle()

        assertEquals(1, fixture.history.size)
        coVerify(exactly = 1) { fixture.api.addHistory(30, 20, 10) }
    }

    @Test
    fun `a new explicit reader session permits one new history visit`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.beginSession(chapter.id)
        fixture.coordinator.record(chapter, 0, 10, 100, true)
        fixture.coordinator.record(chapter, 1, 10, 150, false)
        advanceUntilIdle()
        fixture.coordinator.beginSession(chapter.id)
        fixture.coordinator.record(chapter, 1, 10, 200, true)
        fixture.coordinator.record(chapter, 1, 10, 250, true)
        advanceUntilIdle()

        assertEquals(2, fixture.history.size)
        coVerify(exactly = 2) { fixture.api.addHistory(30, 20, 10) }
    }

    @Test
    fun `restore cancels in-flight uploads and preserves local pending state and history`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        coEvery { fixture.api.chapters(20) } coAnswers {
            entered.complete(Unit)
            CompletableDeferred<List<SmangaChapter>>().await()
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceTimeBy(1000)
        runCurrent()
        entered.await()

        ConnectionRestoreState.duringRestore {
            fixture.coordinator.prepareRestore(listOf(10))
            fixture.coordinator.record(chapter, 5, 10, 200, false)
            fixture.coordinator.setRead(chapter, true)
            fixture.coordinator.retryPending()
        }
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertEquals(0, fixture.history.values.single().status)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.api.addHistory(any(), any(), any()) }
    }

    @Test
    fun `restore invalidates an already running interactive pull`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { fixture.api.chapters(20) } coAnswers {
            entered.complete(Unit)
            response.await()
            listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
        }
        val pull = async { fixture.coordinator.pull(chapter, memo) }
        entered.await()
        ConnectionRestoreState.duringRestore { fixture.coordinator.prepareRestore(listOf(10)) }
        response.complete(Unit)

        assertNull(pull.await())
        assertTrue(fixture.states.isEmpty())
        assertTrue(fixture.applied.isEmpty())
        assertTrue(fixture.history.isEmpty())
    }

    @Test
    fun `configuration invalidation rejects late network progress`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { fixture.api.chapters(20) } coAnswers {
            entered.complete(Unit)
            response.await()
            listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
        }
        val result = CompletableDeferred<Throwable?>()
        val pull = launch { result.complete(runCatching { fixture.coordinator.pull(chapter, memo) }.exceptionOrNull()) }
        entered.await()
        fixture.currentSession = false
        response.complete(Unit)
        pull.join()

        assertTrue(result.await() is CancellationException)
        assertTrue(fixture.states.isEmpty())
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun `manga sync merges per chapter latest while preserving pending and newer local state`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100)
        fixture.states[11] = local(11, 100).copy(pending = true)
        fixture.states[12] = local(12, 300)
        coEvery { fixture.api.chapters(20) } returns listOf(10L, 11L, 12L).map {
            chapter.copy(id = it, latest = SmangaProgress(7, 10, false, 200))
        }

        fixture.coordinator.syncManga(20)

        assertEquals(7, fixture.states.getValue(10).pageIndex)
        assertEquals(2, fixture.states.getValue(11).pageIndex)
        assertEquals(2, fixture.states.getValue(12).pageIndex)
        assertEquals(listOf(10L), fixture.applied.map { it.chapterId })
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 1) { fixture.api.chapters(20) }
        coVerify(exactly = 0) { fixture.api.chapter(any()) }
        coVerify(exactly = 0) { fixture.api.history(any(), any()) }
        coVerify(exactly = 1) { fixture.repository.readStatesForManga(7, "account-hash", 20) }
        coVerify(exactly = 0) { fixture.repository.readStates(any(), any()) }
    }

    @Test
    fun `explicit mark read supports unknown page count and creates no history`() = runTest {
        val fixture = Fixture(this)
        val unknown = chapter.copy(pageCount = 0)
        coEvery { fixture.api.chapters(20) } returns listOf(unknown)

        fixture.coordinator.setRead(unknown, true)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).completed)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 0, 0, true) }
        coVerify(exactly = 0) { fixture.api.addHistory(any(), any(), any()) }
    }

    @Test
    fun `explicit unread calls the unread API and does not fabricate history`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(9, 10, true, 100)))

        fixture.coordinator.setRead(chapter, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).explicitUnread)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 1) { fixture.api.markUnread(20, 10, 10) }
    }

    @Test
    fun `acknowledged reading remains the baseline for unread and read actions after the initial pull`() = runTest {
        val fixture = Fixture(this)
        val publication = chapter.copy(pageCount = 106)
        val localTime = 1_790_064_489_000L
        var serverTime = localTime
        var remote: SmangaProgress? = null
        coEvery { fixture.api.chapters(20) } coAnswers { listOf(publication.copy(latest = remote)) }
        coEvery { fixture.api.pushProgress(20, 10, any(), any(), any()) } coAnswers {
            SmangaProgress(arg(2), arg(3), arg(4), ++serverTime).also { remote = it }
        }
        coEvery { fixture.api.markUnread(20, 10, 106) } coAnswers {
            SmangaProgress(-1, 106, false, ++serverTime).also { remote = it }
        }
        fixture.coordinator.beginSession(10)
        assertFalse(fixture.coordinator.pull(publication, memo)!!.requiresConfirmation)

        fixture.coordinator.record(publication, 53, 106, localTime + 100, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(53, 106, completed = false)
        assertEquals(SmangaProgress(53, 106, false, localTime + 1), remote)

        fixture.coordinator.record(publication, 105, 106, localTime + 200, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(105, 106, completed = true)
        assertEquals(SmangaProgress(105, 106, true, localTime + 2), remote)

        fixture.coordinator.setRead(publication, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(0, 106, completed = false, explicitUnread = true)
        assertEquals(SmangaProgress(-1, 106, false, localTime + 3), remote)

        fixture.coordinator.setRead(publication, true)
        advanceUntilIdle()
        fixture.assertAcknowledged(105, 106, completed = true)
        assertEquals(SmangaProgress(105, 106, true, localTime + 4), remote)
        assertEquals(1, fixture.history.size)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 53, 106, false) }
        coVerify(exactly = 2) { fixture.api.pushProgress(20, 10, 105, 106, true) }
        coVerify(exactly = 1) { fixture.api.markUnread(20, 10, 106) }
        coVerify(exactly = 1) { fixture.api.addHistory(30, 20, 10) }
    }

    @Test
    fun `server clock ahead permits own acknowledgement but rejects a changed remote state`() = runTest {
        val fixture = Fixture(this)
        val localTime = 1_790_064_489_000L
        var serverTime = localTime + 60_000
        var remote: SmangaProgress? = null
        coEvery { fixture.api.chapters(20) } coAnswers { listOf(chapter.copy(latest = remote)) }
        coEvery { fixture.api.pushProgress(20, 10, any(), any(), any()) } coAnswers {
            SmangaProgress(arg(2), arg(3), arg(4), ++serverTime).also { remote = it }
        }
        fixture.coordinator.beginSession(10)
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.record(chapter, 2, 10, localTime + 100, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(2, 10, completed = false)
        assertTrue(remote!!.updatedAt > fixture.states.getValue(10).readAt)

        fixture.coordinator.record(chapter, 3, 10, localTime + 200, false)
        advanceUntilIdle()
        fixture.assertAcknowledged(3, 10, completed = false)

        remote = SmangaProgress(7, 10, false, ++serverTime)
        fixture.coordinator.record(chapter, 4, 10, localTime + 300, false)
        advanceUntilIdle()
        assertTrue(fixture.states.getValue(10).pending)
        assertEquals(4, fixture.states.getValue(10).pageIndex)
        assertEquals(7, remote!!.pageIndex)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 3, 10, false) }
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 4, 10, false) }
    }

    @Test
    fun `explicit unread resolves a persisted mapping pause without fabricating history`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 8, true, 100)))
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.requireMappingConfirmation(10)

        fixture.coordinator.setRead(chapter, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).explicitUnread)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 1) { fixture.api.markUnread(20, 10, 10) }
        coVerify(exactly = 0) { fixture.api.addHistory(any(), any(), any()) }
    }

    @Test
    fun `explicit read clears a mapping pause and accepts the first remote state without a prior pull`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
        fixture.coordinator.requireMappingConfirmation(10)

        fixture.coordinator.setRead(chapter, true)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).completed)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 9, 10, true) }
        coVerify(exactly = 0) { fixture.api.addHistory(any(), any(), any()) }
    }

    @Test
    fun `explicit read and unread choices pause when the known remote state changes again`() = runTest {
        for (read in listOf(false, true)) {
            val fixture = Fixture(this)
            coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
            fixture.coordinator.pull(chapter, memo)
            fixture.coordinator.requireMappingConfirmation(10)
            fixture.coordinator.setRead(chapter, read)
            coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(5, 8, false, 200)))

            advanceUntilIdle()

            assertTrue(fixture.states.getValue(10).pending)
            assertTrue(fixture.history.isEmpty())
            coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.api.markUnread(any(), any(), any()) }
        }
    }

    @Test
    fun `a new session revokes the count override from explicit read and unread choices`() = runTest {
        for (read in listOf(false, true)) {
            val fixture = Fixture(this)
            coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
            fixture.coordinator.pull(chapter, memo)
            fixture.coordinator.setRead(chapter, read)

            fixture.coordinator.beginSession(10)
            advanceUntilIdle()

            assertTrue(fixture.states.getValue(10).pending)
            coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.api.markUnread(any(), any(), any()) }
        }
    }

    @Test
    fun `restore preserves an explicit read choice without starting an upload`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.setRead(chapter, true)

        ConnectionRestoreState.duringRestore { fixture.coordinator.prepareRestore(listOf(10)) }
        advanceUntilIdle()

        assertTrue(fixture.states.containsKey(10))
        assertTrue(fixture.history.isEmpty())
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `interactive pull defers persistence until source version checks and reader acceptance`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 100)))

        val offered = fixture.coordinator.pull(chapter, memo)!!

        assertEquals(7, offered.pageIndex)
        assertTrue(fixture.states.isEmpty())
        assertTrue(fixture.applied.isEmpty())
        fixture.coordinator.accept(chapter, 7, 10, 100)
        assertEquals(7, fixture.states.getValue(10).pageIndex)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
    }

    @Test
    fun `completed remote progress without a count lets the reader select its actual final page`() = runTest {
        val fixture = Fixture(this)
        val unknown = chapter.copy(pageCount = 0, latest = SmangaProgress(0, 0, true, 100))
        coEvery { fixture.api.chapters(20) } returns listOf(unknown)

        val offered = fixture.coordinator.pull(unknown, memo)!!

        assertTrue(offered.completed)
        assertNull(offered.pageIndex)
        assertEquals(0, offered.totalPages)
    }

    @Test
    fun `completed zero count progress uses chapter metadata when a page count is known`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(0, 0, true, 100)))

        val offered = fixture.coordinator.pull(chapter, memo)!!

        assertTrue(offered.completed)
        assertEquals(9, offered.pageIndex)
        assertEquals(10, offered.totalPages)
    }

    @Test
    fun `background retries do not upload positions across changed physical page counts`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(2, 8, false, 50)))

        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `physical page mismatch requests confirmation and preserves manifest metadata`() = runTest {
        val fixture = Fixture(this)
        val metadata = buildJsonObject {
            put("pagesCount", 10)
            put("smangaManifestVersion", "manifest-v2")
        }
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))

        val progress = fixture.coordinator.pull(chapter, metadata)!!

        assertTrue(progress.requiresConfirmation)
        assertEquals("manifest-v2", progress.previousPublicationVersion)
        assertEquals("manifest-v2", progress.publicationVersion)
        assertEquals(metadata, progress.updatedChapterMemo)
        assertTrue(fixture.states.isEmpty())
        assertTrue(fixture.applied.isEmpty())
    }

    @Test
    fun `mapping pause persists ordinary navigation without uploading it`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()
        fixture.coordinator.record(chapter, 6, 10, 200, false)
        advanceUntilIdle()

        assertEquals(6, fixture.states.getValue(10).pageIndex)
        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ordinary navigation cannot implicitly resolve a newer remote conflict`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        fixture.coordinator.record(chapter, 6, 10, 300, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `explicit local confirmation uploads once across old page count and newer timestamp`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 500)))
        fixture.coordinator.requireMappingConfirmation(10)

        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        fixture.coordinator.record(chapter, 3, 10, 200, false)
        advanceUntilIdle()
        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 3, 10, false) }
    }

    @Test
    fun `confirmed local navigation merges rapid page flips until the first successful acknowledgement`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 500)))
        fixture.coordinator.beginSession(10)
        fixture.coordinator.pull(chapter, memo)

        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)
        fixture.coordinator.record(chapter, 3, 10, 150, false)
        fixture.coordinator.record(chapter, 6, 10, 200, false)
        advanceUntilIdle()

        assertEquals(6, fixture.states.getValue(10).pageIndex)
        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 6, 10, false) }
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 3, 10, false) }
        assertEquals(1, fixture.history.size)
    }

    @Test
    fun `a remote change after local confirmation pauses even when ordinary timestamps favor local`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 10, false, 200)))
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 300)
        fixture.coordinator.record(chapter, 6, 10, 400, false)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(5, 10, false, 250)))

        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mapping confirmation binds the last pulled remote state when no remote offer was selected`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 300)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 10, false, 100)))
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 400)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 10, false, 200)))

        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `changed physical page count revokes confirmation and pauses the new local revision`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)
        fixture.coordinator.record(chapter, 3, 12, 200, false)

        advanceUntilIdle()

        assertEquals(12, fixture.states.getValue(10).totalPages)
        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirmed navigation during upload recognizes its own write until acknowledgement`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var remote = chapter.copy(latest = SmangaProgress(4, 8, false, 500))
        coEvery { fixture.api.chapters(20) } coAnswers { listOf(remote) }
        coEvery { fixture.api.pushProgress(20, 10, 2, 10, false) } coAnswers {
            entered.complete(Unit)
            response.await()
            remote = chapter.copy(latest = SmangaProgress(2, 10, false, 600))
            checkNotNull(remote.latest)
        }
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)
        advanceTimeBy(1000)
        runCurrent()
        entered.await()

        fixture.coordinator.record(chapter, 6, 10, 200, false)
        val latestRevision = fixture.states.getValue(10).revision
        response.complete(Unit)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        assertEquals(listOf(latestRevision), fixture.acknowledged)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 6, 10, false) }
    }

    @Test
    fun `interactive pull revokes confirmation when the remote fingerprint changes again`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 500)))
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)
        fixture.coordinator.record(chapter, 6, 10, 200, false)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(5, 8, false, 600)))

        val offered = fixture.coordinator.pull(chapter, memo)!!
        advanceUntilIdle()

        assertEquals(5, offered.pageIndex)
        assertTrue(offered.requiresConfirmation)
        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `remote acceptance clears a pause without creating history`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.accept(chapter, 7, 10, 100)
        assertTrue(fixture.history.isEmpty())
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 100)))

        fixture.coordinator.record(chapter, 8, 10, 200, false)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 8, 10, false) }
    }

    @Test
    fun `accepting an eight page remote position lets physical PDF navigation publish ten pages once`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
        fixture.coordinator.beginSession(10)
        fixture.coordinator.pull(chapter, memo)

        fixture.coordinator.accept(chapter, 4, 10, 100)
        assertFalse(fixture.states.getValue(10).pending)
        assertTrue(fixture.history.isEmpty())
        advanceUntilIdle()
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }

        fixture.coordinator.record(chapter, 5, 10, 200, false)
        fixture.coordinator.record(chapter, 6, 10, 300, false)
        advanceUntilIdle()

        assertFalse(fixture.states.getValue(10).pending)
        assertEquals(6, fixture.states.getValue(10).pageIndex)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 6, 10, false) }
        fixture.coordinator.record(chapter, 7, 10, 400, false)
        advanceUntilIdle()
        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 7, 10, false) }
    }

    @Test
    fun `a later remote update pauses navigation after accepting a mapped remote position`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.accept(chapter, 4, 10, 100)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(5, 8, false, 200)))

        fixture.coordinator.record(chapter, 6, 10, 300, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertTrue(fixture.coordinator.pull(chapter, memo)!!.requiresConfirmation)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a new reader session cannot inherit the accepted remote page mapping`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 100)))
        fixture.coordinator.pull(chapter, memo)
        fixture.coordinator.accept(chapter, 4, 10, 100)

        fixture.coordinator.beginSession(10)
        fixture.coordinator.record(chapter, 5, 10, 200, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a recreated coordinator and new reader session preserve an unresolved pause`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceUntilIdle()
        val reopened = fixture.createCoordinator(this)
        reopened.beginSession(10)

        reopened.record(chapter, 3, 10, 200, false)
        reopened.retryPending()
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
        reopened.confirmLocal(chapter, 3, 10, 300)
        advanceUntilIdle()
        assertFalse(fixture.states.getValue(10).pending)
        coVerify(exactly = 1) { fixture.api.pushProgress(20, 10, 3, 10, false) }
    }

    @Test
    fun `pausing while a fetch is running prevents the subsequent upload`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { fixture.api.chapters(20) } coAnswers {
            entered.complete(Unit)
            response.await()
            listOf(chapter)
        }
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        advanceTimeBy(1000)
        runCurrent()
        entered.await()

        fixture.coordinator.requireMappingConfirmation(10)
        response.complete(Unit)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unresolved pause still needs explicit confirmation at the same remote page`() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.record(chapter, 2, 10, 100, false)
        fixture.coordinator.requireMappingConfirmation(10)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(2, 10, false, 100)))

        val offered = fixture.coordinator.pull(chapter, memo)!!
        advanceUntilIdle()

        assertEquals(2, offered.pageIndex)
        assertTrue(offered.requiresConfirmation)
        assertTrue(offered.requiresPageMappingConfirmation)
        assertTrue(fixture.states.getValue(10).pending)
        assertTrue(fixture.acknowledged.isEmpty())
    }

    @Test
    fun `a new reader session cannot reuse the previous one shot override`() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(4, 8, false, 500)))
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.confirmLocal(chapter, 2, 10, 100)

        fixture.coordinator.beginSession(10)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `restore retains confirmation gates for restored and other chapters`() = runTest {
        val fixture = Fixture(this)
        val other = chapter.copy(id = 11)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter, other)
        fixture.coordinator.requireMappingConfirmation(10)
        fixture.coordinator.requireMappingConfirmation(11)
        ConnectionRestoreState.duringRestore { fixture.coordinator.prepareRestore(listOf(10)) }

        fixture.coordinator.record(chapter, 2, 10, 100, false)
        fixture.coordinator.record(other, 2, 10, 100, false)
        advanceUntilIdle()

        assertTrue(fixture.states.getValue(10).pending)
        assertTrue(fixture.states.getValue(11).pending)
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 10, 2, 10, false) }
        coVerify(exactly = 0) { fixture.api.pushProgress(20, 11, 2, 10, false) }
    }

    @Test
    fun `background manga refresh cannot apply progress while mapping awaits confirmation`() = runTest {
        val fixture = Fixture(this)
        fixture.states[10] = local(10, 100)
        coEvery { fixture.api.chapters(20) } returns listOf(chapter.copy(latest = SmangaProgress(7, 10, false, 200)))
        fixture.coordinator.requireMappingConfirmation(10)

        fixture.coordinator.syncManga(20)

        assertEquals(2, fixture.states.getValue(10).pageIndex)
        assertTrue(fixture.applied.isEmpty())
    }

    private fun local(id: Long, at: Long) = SmangaReadState(id, 20, 2, 10, false, at, 1, false)

    private fun Fixture.assertAcknowledged(
        pageIndex: Int,
        totalPages: Int,
        completed: Boolean,
        explicitUnread: Boolean = false,
    ) {
        val state = states.getValue(chapter.id)
        assertEquals(pageIndex, state.pageIndex)
        assertEquals(totalPages, state.totalPages)
        assertEquals(completed, state.completed)
        assertEquals(explicitUnread, state.explicitUnread)
        assertFalse(state.pending)
        val pause = cache["reading-confirmation" to chapter.id.toString()]?.let {
            Json.parseToJsonElement(it.payload).jsonObject["required"]?.jsonPrimitive?.booleanOrNull
        }
        assertFalse(pause == true)
    }

    private inner class Fixture(scope: CoroutineScope) {
        val api = mockk<SmangaApi>()
        val repository = mockk<SmangaRepository>()
        val states = mutableMapOf<Long, SmangaReadState>()
        val history = mutableMapOf<String, SmangaHistoryEvent>()
        val cache = mutableMapOf<Pair<String, String>, SmangaCacheEntry>()
        val applied = mutableListOf<SmangaReadState>()
        val acknowledged = mutableListOf<Long>()
        var currentSession = true
        val coordinator = createCoordinator(scope)

        fun createCoordinator(scope: CoroutineScope) = SmangaReadingCoordinator(
            7,
            "account-hash",
            api,
            repository,
            scope,
            checkSession = { if (!currentSession) throw CancellationException("Session replaced") },
            applyState = { applied += it },
        )

        init {
            coEvery { repository.cache(7, "account-hash", any(), any()) } answers {
                cache[arg<String>(2) to arg<String>(3)]
            }
            coEvery { repository.putCache(7, "account-hash", any(), any()) } answers {
                val value = arg<SmangaCacheEntry>(3)
                cache[thirdArg<String>() to value.key] = value
            }
            coEvery { repository.readState(7, "account-hash", any()) } answers { states[thirdArg()] }
            coEvery { repository.readStates(7, "account-hash") } answers { states.values.toList() }
            coEvery { repository.pendingReadStates(7, "account-hash") } answers { states.values.filter { it.pending } }
            coEvery { repository.readStatesForManga(7, "account-hash", any()) } answers {
                val mangaId = thirdArg<Long>()
                states.values.filter { it.mangaId == mangaId }
            }
            coEvery { repository.putReadState(7, "account-hash", any()) } answers {
                val value = thirdArg<SmangaReadState>()
                states[value.chapterId] = value
            }
            coEvery { repository.acknowledgeReadState(7, "account-hash", any(), any()) } answers {
                val id = thirdArg<Long>()
                val revision = arg<Long>(3)
                val prior = states[id]
                if (prior?.pending == true && prior.revision == revision) {
                    acknowledged += revision
                    states[id] = prior.copy(pending = false)
                    true
                } else {
                    false
                }
            }
            coEvery { repository.resetReadStates(7, "account-hash", any()) } answers {
                thirdArg<List<Long>>().forEach(states::remove)
            }
            coEvery { repository.enqueueHistory(7, "account-hash", any()) } answers {
                val value = thirdArg<SmangaHistoryEvent>()
                history.putIfAbsent(value.id, value)
                Unit
            }
            coEvery { repository.historyEvents(7, "account-hash") } answers { history.values.toList() }
            coEvery { repository.pendingHistoryEvents(7, "account-hash") } answers {
                history.values.filter { it.status == 0 }
            }
            coEvery { repository.historyEvent(7, "account-hash", any()) } answers { history[thirdArg<String>()] }
            coEvery { repository.updateHistoryStatus(7, "account-hash", any(), any()) } answers {
                val id = thirdArg<String>()
                val status = arg<Int>(3)
                history[id]?.let { history[id] = it.copy(status = maxOf(status, it.status)) }
                Unit
            }
            coEvery { api.chapters(20) } returns listOf(chapter)
            coEvery { api.pushProgress(any(), any(), any(), any(), any()) } coAnswers {
                SmangaProgress(arg(2), arg(3), arg(4))
            }
            coEvery { api.markUnread(any(), any(), any()) } coAnswers {
                SmangaProgress(-1, arg(2), false)
            }
            coEvery { api.addHistory(any(), any(), any()) } returns Unit
        }
    }
}
