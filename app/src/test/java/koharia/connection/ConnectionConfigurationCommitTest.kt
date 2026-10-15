package koharia.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionConfigurationCommitTest {
    private class Storage : SharedConfigStorage {
        val data = mutableMapOf<String, Any?>()
        var fail = false
        override fun values() = data.toMap()
        override fun commit(values: Map<String, Any?>): Boolean {
            if (fail) return false
            values.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
            return true
        }
    }

    private val journalStorage = Storage()
    private var journal = ConnectionIdentityChanges(journalStorage, Json)
    private val live = Storage().apply { data.putAll(mapOf("address" to "old", "cache" to 1)) }
    private val draft = Storage()
    private fun coordinator() = ConnectionConfigurationCommit(journal, { live }, { draft })

    @Test fun `draft stays private and publication preserves unrelated concurrent state`() = runTest {
        var retired = false
        coordinator().commit(1, replaceSource = { block ->
            retired = true
            assertTrue(journal.isPaused(1))
            block()
            assertEquals("new", live.data["address"])
            assertTrue(journal.isPaused(1))
        }) {
            assertTrue(retired)
            draft.commit(mapOf("address" to "new", "credential" to "fixture-secret"))
            live.commit(mapOf("cache" to 2))
            assertEquals("old", live.data["address"])
            assertFalse("credential" in live.data)
        }
        assertEquals(2, live.data["cache"])
        assertFalse(journal.isPaused(1))
        assertTrue(draft.data.isEmpty())
        assertFalse(journalStorage.data.toString().contains("fixture-secret"))
    }

    @Test fun `cancelled draft keeps old configuration and removes only its transient pause`() = runTest {
        journal.prepareImport(listOf(LibraryConnectionProfile(1, "fixture", "Imported")))
        journal.readyImported(setOf(1))
        val failure = runCatching {
            coordinator().commit(1, { it() }) {
                draft.commit(mapOf("address" to "partial"))
                throw CancellationException("Cancelled editor")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("old", live.data["address"])
        assertTrue(journal.pendingConfigurations().isEmpty())
        assertTrue(journal.isPaused(1))
        assertTrue(draft.data.isEmpty())
    }

    @Test fun `failed live write remains durable and is replayed before reopening a source`() = runTest {
        live.fail = true
        assertTrue(
            runCatching {
                coordinator().commit(1, { it() }) { draft.commit(mapOf("address" to "new")) }
            }.isFailure,
        )
        journal = ConnectionIdentityChanges(journalStorage, Json)
        assertTrue(journal.isPaused(1))
        assertEquals(setOf("address"), journal.pendingConfigurations().single().keys)
        assertEquals("old", live.data["address"])
        live.fail = false
        coordinator().recover(1)
        assertEquals("new", live.data["address"])
        assertTrue(journal.isPaused(1))
        coordinator().finishRecovery(1)
        assertFalse(journal.isPaused(1))
    }

    @Test fun `failed source publication keeps new settings paused and retry finishes recovery first`() = runTest {
        assertTrue(
            runCatching {
                coordinator().commit(1, { block ->
                    block()
                    error("Source creation failed")
                }) {
                    draft.commit(mapOf("address" to "prepared", "cache" to null))
                }
            }.isFailure,
        )
        assertTrue(journal.isPaused(1))
        assertEquals("prepared", live.data["address"])
        assertFalse("cache" in live.data)
        var publications = 0
        coordinator().commit(1, { block ->
            publications++
            block()
        }) {
            assertEquals("prepared", live.data["address"])
            draft.commit(mapOf("address" to "final"))
        }
        assertEquals(2, publications)
        assertEquals("final", live.data["address"])
        assertFalse(journal.isPaused(1))
    }

    @Test fun `missing prepared payload fails closed and leaves other owners unpaused`() = runTest {
        assertTrue(
            runCatching {
                coordinator().commit(1, { block ->
                    block()
                    error("Stopped")
                }) { draft.commit(mapOf("address" to "new")) }
            }.isFailure,
        )
        draft.data.clear()
        journal = ConnectionIdentityChanges(journalStorage, Json)
        assertThrows(IllegalStateException::class.java) { coordinator().recover(1) }
        assertTrue(journal.isPaused(1))
        assertFalse(journal.isPaused(2))
    }
}
