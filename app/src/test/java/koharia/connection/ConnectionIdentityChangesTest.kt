package koharia.connection

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionIdentityChangesTest {
    @Test fun verifiedActivationPersistsWithoutReleasingOtherOwnersAndCanBePausedAgain() {
        val imported = LibraryConnectionProfile(11, "test", "Imported")
        journal.prepareImport(listOf(imported))
        journal.readyImported(setOf(11))
        journal.published(11)
        journal.prepare(10, LibraryConnectionProfile(12, "test", "Replacement"))
        journal.ready(12)
        journal.published(12)
        journal.activateVerified(10)
        val restarted = ConnectionIdentityChanges(storage, Json)
        assertFalse(restarted.isPaused(10))
        assertTrue(restarted.isPaused(11))
        restarted.activateVerified(11)
        assertFalse(ConnectionIdentityChanges(storage, Json).isPaused(11))
        restarted.prepare(10, LibraryConnectionProfile(13, "test", "Another replacement"))
        restarted.ready(13)
        assertTrue(restarted.isPaused(10))
    }

    @Test fun activationRejectsUnpublishedOrUnfinishedConfigurationAndFailedDurability() {
        journal.prepareImport(listOf(LibraryConnectionProfile(11, "test", "Imported")))
        journal.readyImported(setOf(11))
        assertThrows(IllegalStateException::class.java) { journal.activateVerified(11) }
        journal.published(11)
        journal.beginConfiguration(11)
        assertThrows(IllegalStateException::class.java) { journal.activateVerified(11) }
        journal.finishConfiguration(11)
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { journal.activateVerified(11) }
        assertTrue(journal.isPaused(11))
        assertTrue(ConnectionIdentityChanges(storage, Json).isPaused(11))
    }
    private class Storage : SharedConfigStorage {
        val data = mutableMapOf<String, Any?>()
        var fail = false
        override fun values() = data.toMap()
        override fun commit(values: Map<String, Any?>): Boolean {
            if (fail) return false
            data.putAll(values)
            return true
        }
    }

    private val storage = Storage()
    private val journal = ConnectionIdentityChanges(storage, Json)
    private val previous = LibraryConnectionProfile(10, "test", "Home")
    private var inventoryDurable = true
    private var settingsDurable = true
    private val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json) { inventoryDurable }.apply {
        setProfiles(listOf(previous))
    }
    private val provider = mockk<ConnectionProvider> {
        every { id } returns "test"
        every { isConnectionNameAvailable(any()) } returns true
        every { directoryNameFor(any()) } answers { firstArg<String>().replace('/', '_') }
    }
    private fun manager(changes: ConnectionIdentityChanges = journal) = ConnectionProfileManager(
        preferences,
        ConnectionRegistry(listOf(provider)),
        mockk(),
        changes,
        flushSettings = { settingsDurable },
    )

    @Test
    fun `replacement never publishes settings under old owner and retains old inventory`() = runTest {
        val replacement = manager().replaceIdentity(previous.id, previous.name) { id ->
            assertNotEquals(previous.id, id)
            assertEquals(listOf(previous), preferences.getProfiles())
            assertFalse(journal.isPaused(previous.id))
        }
        assertEquals("Home (2)", replacement.name)
        assertEquals(listOf(previous, replacement), preferences.getProfiles())
        assertEquals(replacement.id, preferences.activeConnectionId.get())
        assertTrue(journal.isPaused(previous.id))
        assertTrue(journal.pending().isEmpty())
        assertTrue(ConnectionIdentityChanges(storage, Json).isPaused(previous.id))
    }

    @Test
    fun `failed settings stay unpublished and reserve their identity across restart`() = runTest {
        var reserved = 0L
        val failure = runCatching {
            manager().replaceIdentity(previous.id, "New") {
                reserved = it
                throw IllegalStateException("injected write failure")
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf(previous), preferences.getProfiles())
        val restarted = ConnectionIdentityChanges(storage, Json)
        assertTrue(restarted.isReserved(reserved))
        assertFalse(restarted.isPaused(previous.id))
        manager(restarted).recoverIdentityChanges()
        assertEquals(listOf(previous), preferences.getProfiles())
    }

    @Test
    fun `configuration recovery pause is distinct from an imported identity requiring a new connection`() = runTest {
        journal.beginConfiguration(previous.id)
        assertTrue(journal.isPaused(previous.id))
        assertFalse(manager().requiresIdentityReplacement(previous.id))
        journal.finishConfiguration(previous.id)
        val imported = manager().importConnections(listOf(previous)) {}.getValue(previous.id)
        assertTrue(manager().requiresIdentityReplacement(imported.id))
        assertFalse(manager().requiresIdentityReplacement(previous.id))

        val oldInventory = preferences.getProfiles()
        val next = manager().replaceIdentity(imported.id, imported.name) { newId ->
            assertNotEquals(imported.id, newId)
            assertEquals(oldInventory, preferences.getProfiles())
        }
        val restarted = ConnectionIdentityChanges(storage, Json)
        assertTrue(restarted.isIdentityPaused(imported.id))
        assertFalse(restarted.isPaused(next.id))
        assertTrue(preferences.getProfiles().containsAll(oldInventory))
    }

    @Test
    fun `failed settings flush does not release replacement`() = runTest {
        settingsDurable = false
        assertTrue(runCatching { manager().replaceIdentity(previous.id, "New") {} }.isFailure)
        assertEquals(listOf(previous), preferences.getProfiles())
        assertFalse(journal.isPaused(previous.id))
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `ready journal repairs interrupted publication and is idempotent`() {
        val replacement = previous.copy(id = 11, name = "New")
        journal.prepare(previous.id, replacement)
        journal.ready(replacement.id)
        val restarted = ConnectionIdentityChanges(storage, Json)
        manager(restarted).recoverIdentityChanges()
        manager(restarted).recoverIdentityChanges()
        assertEquals(listOf(previous, replacement), preferences.getProfiles())
        assertTrue(restarted.pending().isEmpty())
        assertTrue(restarted.isPaused(previous.id))
    }

    @Test
    fun `inventory flush failure retains recovery record and remote write pause`() = runTest {
        inventoryDurable = false
        assertTrue(runCatching { manager().replaceIdentity(previous.id, "New") {} }.isFailure)
        assertEquals(1, journal.pending().size)
        assertTrue(journal.isPaused(previous.id))
        inventoryDurable = true
        manager(ConnectionIdentityChanges(storage, Json)).recoverIdentityChanges()
        assertEquals(2, preferences.getProfiles().size)
        assertTrue(ConnectionIdentityChanges(storage, Json).pending().isEmpty())
    }

    @Test
    fun `concurrent replacements have separate names and ids`() = runTest {
        val manager = manager()
        val profiles = (1..3).map { async { manager.replaceIdentity(previous.id, "Home") {} } }.awaitAll()
        assertEquals(3, profiles.map { it.name }.distinct().size)
        assertEquals(3, profiles.map { it.id }.distinct().size)
        assertEquals(4, preferences.getProfiles().size)
    }

    @Test
    fun `import stages every owner before publishing and does not pause unrelated existing connections`() = runTest {
        val incoming = listOf(previous, previous.copy(id = 11, name = "Second"))
        val targets = manager().importConnections(incoming) { mapping ->
            assertEquals(listOf(previous), preferences.getProfiles())
            assertTrue(mapping.values.none { it.id in setOf(10L, 11L) })
            assertFalse(journal.isPaused(previous.id))
        }
        assertEquals(3, preferences.getProfiles().size)
        assertEquals(previous.id, preferences.activeConnectionId.get())
        assertFalse(journal.isPaused(previous.id))
        val restarted = ConnectionIdentityChanges(storage, Json)
        assertTrue(targets.values.all { restarted.isPaused(it.id) })
    }

    @Test
    fun `batch import reserves normalized directory names before profiles are published`() = runTest {
        val targets = manager().importConnections(
            listOf(
                previous.copy(id = 17, name = "A/B"),
                previous.copy(id = 18, name = "A_B"),
            ),
        ) {}
        assertEquals("A/B", targets.getValue(17).name)
        assertEquals("A_B (2)", targets.getValue(18).name)
        assertEquals(2, targets.values.map { provider.directoryNameFor(it.name) }.distinct().size)
    }

    @Test
    fun `interrupted import publishes none of its partially written configurations`() = runTest {
        var reserved = emptySet<Long>()
        assertTrue(
            runCatching {
                manager().importConnections(listOf(previous, previous.copy(id = 11))) { targets ->
                    reserved = targets.values.mapTo(mutableSetOf()) { it.id }
                    error("injected interruption")
                }
            }.isFailure,
        )
        val restarted = ConnectionIdentityChanges(storage, Json)
        manager(restarted).recoverIdentityChanges()
        assertEquals(listOf(previous), preferences.getProfiles())
        assertTrue(reserved.all(restarted::isReserved))
        assertTrue(restarted.pending().isEmpty())
    }

    @Test
    fun `interrupted database import resumes exact reserved owners after restart`() = runTest {
        val imported = listOf(previous, previous.copy(id = 11, name = "Second"))
        var firstTargets = emptyMap<Long, LibraryConnectionProfile>()
        val committedRows = mutableSetOf<Pair<Long, String>>()
        assertTrue(
            runCatching {
                manager().importConnections(imported, recoveryKey = "payload-and-options") { targets ->
                    firstTargets = targets
                    committedRows += targets.getValue(10).id to "first"
                    error("process stopped after first entry transaction")
                }
            }.isFailure,
        )
        val restarted = ConnectionIdentityChanges(storage, Json)
        val resumed = manager(restarted).importConnections(imported, recoveryKey = "payload-and-options") { targets ->
            assertEquals(firstTargets, targets)
            assertEquals(listOf(previous), preferences.getProfiles())
            committedRows += targets.getValue(10).id to "first"
            committedRows += targets.getValue(11).id to "second"
        }
        assertEquals(2, committedRows.size)
        assertEquals(firstTargets, resumed)
        assertTrue(resumed.values.all { restarted.isPaused(it.id) })
        assertEquals(null, restarted.interruptedImport("payload-and-options"))
    }

    @Test
    fun `different payload reserves separate names and cannot claim interrupted owners`() = runTest {
        var firstTarget: LibraryConnectionProfile? = null
        runCatching {
            manager().importConnections(listOf(previous), recoveryKey = "first-backup") {
                firstTarget = it.getValue(previous.id)
                error("interrupted")
            }
        }
        val otherTargets = manager().importConnections(listOf(previous), recoveryKey = "changed-backup") {}
        val other = otherTargets.getValue(previous.id)
        assertNotEquals(firstTarget!!.id, other.id)
        assertNotEquals(firstTarget!!.name, other.name)
        assertTrue(journal.interruptedImport("first-backup") != null)
        val mismatch = runCatching {
            manager().importConnections(
                listOf(previous.copy(providerId = "other")),
                recoveryKey = "first-backup",
            ) {}
        }
        assertTrue(mismatch.isFailure)
    }

    @Test
    fun `interrupted import names cannot be claimed by add rename update or replacement`() = runTest {
        val manager = manager()
        var reserved: LibraryConnectionProfile? = null
        runCatching {
            manager.importConnections(listOf(previous.copy(id = 17, name = "A/B")), recoveryKey = "interrupted") {
                reserved = it.getValue(17)
                error("stop")
            }
        }
        val added = manager.add("test", "a_b")
        assertEquals("a_b (2)", added.name)
        assertThrows(IllegalArgumentException::class.java) { manager.update(previous.copy(name = "A_B")) }
        assertTrue(runCatching { manager.rename(previous.id, "A_B") { _, _ -> error("must not move") } }.isFailure)
        val replacement = manager.replaceIdentity(previous.id, "A_B") {}
        assertEquals("A_B (3)", replacement.name)
        val resumed = manager.importConnections(
            listOf(previous.copy(id = 17, name = "A/B")),
            recoveryKey = "interrupted",
        ) {
        }
        assertEquals(reserved, resumed.getValue(17))
    }

    @Test
    fun `rename reserves target before file IO and preserves concurrent additions`() = runTest {
        val manager = manager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rename = async {
            manager.rename(previous.id, "A/B") { _, _ ->
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertEquals(previous, preferences.getProfiles().single())
        val added = manager.add("test", "A_B")
        assertEquals("A_B (2)", added.name)
        assertThrows(IllegalStateException::class.java) { manager.update(previous.copy(name = "Other")) }
        release.complete(Unit)
        rename.await()
        assertEquals(listOf(previous.copy(name = "A/B"), added), preferences.getProfiles())
        val restarted = manager(ConnectionIdentityChanges(storage, Json))
        assertEquals("Home (2)", restarted.add("test", "Home").name)
    }

    @Test
    fun `failed rename does not publish the target and retains its reservation after restart`() = runTest {
        assertTrue(
            runCatching {
                manager().rename(previous.id, "Destination") { _, _ -> error("failed IO") }
            }.isFailure,
        )
        assertEquals(listOf(previous), preferences.getProfiles())
        val restarted = manager(ConnectionIdentityChanges(storage, Json))
        assertEquals("Destination (2)", restarted.add("test", "Destination").name)
        restarted.update(previous.copy(name = "Available"))
        assertEquals("Available", preferences.getProfiles().first().name)
    }

    @Test
    fun `removal blocks same profile edits while allowing unrelated additions`() = runTest {
        val manager = manager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { provider.removeConnection(previous) } coAnswers {
            entered.complete(Unit)
            release.await()
            preferences.mutateProfiles { it.filterNot { profile -> profile.id == previous.id } }
            Result.success(true)
        }
        val removal = async { manager.remove(previous.id) }
        entered.await()
        val added = manager.add("test", "Other")
        assertThrows(IllegalStateException::class.java) { manager.update(previous.copy(name = "Renamed")) }
        release.complete(Unit)
        assertTrue(removal.await().isSuccess)
        assertEquals(listOf(added), preferences.getProfiles())
        assertEquals("Home (2)", manager(ConnectionIdentityChanges(storage, Json)).add("test", "Home").name)
    }

    @Test
    fun `ready import recovers every connection with writes held after restart`() {
        val incoming = listOf(previous.copy(id = 11), previous.copy(id = 12))
        journal.prepareImport(incoming)
        journal.readyImported(setOf(11, 12))
        val restarted = ConnectionIdentityChanges(storage, Json)
        manager(restarted).recoverIdentityChanges()
        assertEquals(listOf(previous) + incoming, preferences.getProfiles())
        assertTrue(restarted.isPaused(11) && restarted.isPaused(12))
        assertFalse(restarted.isPaused(10))
    }

    @Test
    fun `normal allocation skips unpublished identity journal reservations`() {
        journal.prepare(10, previous.copy(id = 1))
        journal.prepare(10, previous.copy(id = 2))
        val restarted = ConnectionPreferences(MutableTestPreferenceStore(), Json, isReserved = journal::isReserved)
        assertEquals(3L, restarted.allocateConnectionId())
        assertEquals(4L, restarted.allocateConnectionId())
    }

    @Test
    fun `journal disk failure leaves current state unchanged`() {
        val replacement = previous.copy(id = 11)
        journal.prepare(previous.id, replacement)
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { journal.ready(11) }
        assertFalse(journal.isPaused(previous.id))
        assertFalse(ConnectionIdentityChanges(storage, Json).isPaused(previous.id))
    }

    @Test
    fun `journal corruption fails closed and id collisions are not overwritten`() {
        journal.prepare(previous.id, previous.copy(id = 11))
        journal.ready(11)
        preferences.setProfiles(listOf(previous, previous.copy(id = 11, name = "Unrelated")))
        assertThrows(IllegalStateException::class.java) { manager().recoverIdentityChanges() }
        assertEquals("Unrelated", preferences.getProfiles().last().name)
        storage.data["replacements"] = "invalid"
        assertThrows(Exception::class.java) { ConnectionIdentityChanges(storage, Json) }
    }
}
