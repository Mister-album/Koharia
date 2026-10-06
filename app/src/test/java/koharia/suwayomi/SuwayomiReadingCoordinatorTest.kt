package koharia.suwayomi

import koharia.connection.ConnectionRestoreState
import koharia.domain.suwayomi.SuwayomiOperation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiReadingCoordinatorTest {
    private val repository = MemorySuwayomiRepository()
    private val api = FakeSuwayomiService()
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = SuwayomiIdentity(4, "a".repeat(64))
    private val memo = JsonObject(emptyMap())
    private fun TestScope.coordinator() = SuwayomiReadingCoordinator(identity, repository, api, json, backgroundScope, {
    }, {})

    @Test
    fun `zero based first and last page progress keeps completed chapters read during rereading`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 9, 10, 100, false)
        reader.flush()
        assertEquals(9, api.remote.lastPageRead)
        assertTrue(api.remote.isRead)
        reader.record(3, 11, 0, 10, 101, false)
        reader.flush()
        assertEquals(0, api.remote.lastPageRead)
        assertTrue(api.remote.isRead)
        reader.markRead(3, 11, false)
        reader.flush()
        assertFalse(api.remote.isRead)
        assertEquals(0, api.remote.lastPageRead)
    }

    @Test
    fun `initial activation never overwrites a remote resume position`() = runTest {
        api.remote = api.remote.copy(lastPageRead = 5, lastReadAt = 10)
        val reader = coordinator()
        reader.record(3, 11, 0, 10, 100, true)
        reader.flush()
        assertEquals(0, api.updates)
        val snapshot = reader.pull(3, 11, memo)
        assertEquals(5, snapshot.pageIndex)
        assertFalse(snapshot.requiresConfirmation)
        reader.flush()
        assertEquals(0, api.updates)
    }

    @Test
    fun `offline local and changed remote progress require explicit selection`() = runTest {
        var reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.remote = api.remote.copy(lastPageRead = 7, lastReadAt = 50)
        reader = coordinator()
        reader.flush()
        assertEquals(0, api.updates)
        assertTrue(reader.pull(3, 11, memo).requiresConfirmation)
        reader.confirm(3, 11, 2, 10, 101)
        reader.flush()
        assertEquals(2, api.remote.lastPageRead)
        assertFalse(reader.operation(11)!!.pending)
    }

    @Test
    fun `accepting remote does not create an upload`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.remote = api.remote.copy(lastPageRead = 8, lastReadAt = 50)
        reader.pull(3, 11, memo)
        reader.accept(3, 11, 8, 10, 50_000)
        reader.flush()
        assertEquals(0, api.updates)
        assertFalse(reader.operation(11)!!.pending)
    }

    @Test
    fun `old acknowledgement cannot erase a newer page flip`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.onUpdate = { reader.record(3, 11, 3, 10, 101, false) }
        reader.flush()
        assertTrue(reader.operation(11)!!.pending)
        api.onUpdate = {}
        reader.flush()
        assertEquals(3, api.remote.lastPageRead)
        assertFalse(reader.operation(11)!!.pending)
    }

    @Test
    fun `unknown mutation outcome is read back before retrying`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.onUpdate = {
            api.remote = api.remote.copy(lastPageRead = 2, lastReadAt = 50)
            throw java.io.IOException("lost response")
        }
        try {
            reader.flush()
        } catch (_: java.io.IOException) { }
        assertTrue(reader.operation(11)!!.pending)
        reader.flush()
        assertFalse(reader.operation(11)!!.pending)
        assertEquals(1, api.updates)
    }

    @Test
    fun `restore and mapping conflicts pause all background writes`() = runTest {
        val state = SuwayomiReadState(3, 11, 2, 10, false, 100, conflict = true)
        repository.putOperation(4, identity.account, SuwayomiOperation("11", json.encodeToString(state), 1, true))
        val reader = coordinator()
        reader.flush()
        assertEquals(0, api.updates)
        assertTrue(reader.pull(3, 11, memo).requiresConfirmation)
        ConnectionRestoreState.duringRestore {
            reader.confirm(3, 11, 2, 10, 100)
            reader.flush()
        }
        assertEquals(0, api.updates)
    }

    @Test
    fun `ordinary reader push cannot clear a background conflict`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.remote = api.remote.copy(lastPageRead = 7, lastReadAt = 50)
        reader.flush()
        reader.readerReady(3, 11, 2, 10)
        reader.record(3, 11, 3, 10, 101, false)
        reader.readerReady(3, 11, 3, 10)
        reader.flush()
        assertEquals(0, api.updates)
        assertTrue(reader.pull(3, 11, memo).requiresConfirmation)
        reader.confirm(3, 11, 3, 10, 102)
        reader.flush()
        assertEquals(3, api.remote.lastPageRead)
    }

    @Test
    fun `page count changes pause upload even if remote position is unchanged`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.remote = api.remote.copy(pageCount = 12)
        reader.flush()
        reader.readerReady(3, 11, 2, 12)
        reader.flush()
        assertEquals(0, api.updates)
        assertTrue(reader.operation(11)!!.pending)
    }

    @Test
    fun `opening a chapter cannot lose a pending explicit unread action`() = runTest {
        api.remote = api.remote.copy(isRead = true, lastPageRead = 9)
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.markRead(3, 11, false)
        reader.record(3, 11, 0, 10, 100, true)
        reader.readerReady(3, 11, 0, 10)
        reader.flush()
        assertFalse(api.remote.isRead)
        assertEquals(0, api.remote.lastPageRead)
    }

    @Test
    fun `a changed page count in an upload response leaves pending progress paused`() = runTest {
        val reader = coordinator()
        reader.pull(3, 11, memo)
        reader.record(3, 11, 2, 10, 100, false)
        api.onUpdate = { api.remote = api.remote.copy(pageCount = 12) }
        reader.flush()
        assertTrue(reader.operation(11)!!.pending)
        assertTrue(json.decodeFromString<SuwayomiReadState>(reader.operation(11)!!.payload).conflict)
        reader.readerReady(3, 11, 2, 12)
        reader.flush()
        assertEquals(1, api.updates)
    }
}
