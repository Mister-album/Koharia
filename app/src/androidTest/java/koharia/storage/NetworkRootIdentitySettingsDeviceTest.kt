package koharia.storage

import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.storage.LibraryStorageRepository
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.NetworkStorageDraft
import koharia.source.local.saveNetworkLibraryDraft
import koharia.source.local.withInitialBookshelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NetworkRootIdentitySettingsDeviceTest {
    @Test
    fun settingsPreserveOnlyVerifiedOwnerAndIsolateChangedRootsAndAddresses(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("rootIdentityAddress")
        assumeTrue(address != null)
        val base = checkNotNull(address).trimEnd('/')
        require(base.startsWith("http://127.0.0.1:") || base.startsWith("smb://127.0.0.1:"))
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("rootIdentityMode")))
        require(mode in setOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val manager = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val repository = Injekt.get<LibraryStorageRepository>()
        val inventory = manager.profiles()
        val hadActive = preferences.activeConnectionId.isSet()
        val active = preferences.activeConnectionId.get()
        val prefix = "Network root identity ${System.nanoTime()}"
        val owned = mutableSetOf<Long>()
        val json = Injekt.get<Json>()
        val rootA = "e13f2ba1-8040-4bca-960a-5ac54ec78c34"
        val rootB = "a8e5bd46-2165-4f28-9b54-dda71cef8b65"
        suspend fun assertBytes(runtime: NetworkStorageRuntime) {
            val entry = runtime.backend.stat("book.bin")
            assertTrue(runtime.backend.read(entry, 0, 64).contentEquals(ByteArray(64) { 3 }))
            assertEquals(runtime.config.rootIdentity, StorageIdentity.read(runtime.backend))
        }
        try {
            val original = manager.add("local-folder", prefix)
            owned += original.id
            val initial = NetworkStorageConfiguration(mode, "$base/root-a/")
            var savedId = original.id
            val library = saveNetworkLibraryDraft(
                context,
                original.id,
                NetworkStorageDraft(initial, "root", "fixture-179"),
                initial,
                LocalLibraryConfig().withInitialBookshelves("Comics", "Books"),
                emptyMap(),
                true,
                onSaved = { savedId = it },
            )
            assertEquals(original.id, savedId)
            assertEquals(rootA, library.libraryId)
            val assignments = mapOf("book" to library.bookshelves.first().id)
            LocalLibraryPreferences(original.id, json).saveLibraryDraft(library, assignments)
            val runtime = NetworkStorageRuntime.get(context, original.id)
            assertBytes(runtime)
            runtime.records.put(
                "progress",
                "book",
                StoragePendingProgress(
                    StorageProgressRecord("book", "v1", "bcd28e35-3ea3-4a34-b6cb-9ce50a739ed9", 1, 100, 3, 10),
                    pending = true,
                    requiresConfirmation = true,
                ),
                1,
            )
            val pending = checkNotNull(runtime.records.snapshot("progress", "book"))
            val account = runtime.account
            val firstConfig = NetworkStoragePreferences(original.id).configuration
            // A spelling-only change is the same parsed endpoint and retains its complete local state.
            saveNetworkLibraryDraft(
                context,
                original.id,
                NetworkStorageDraft(firstConfig.copy(address = "$base/root-a"), "root", "fixture-179"),
                firstConfig,
                library,
                assignments,
                false,
                onSaved = { savedId = it },
            )
            assertEquals(original.id, savedId)
            val retained = NetworkStorageRuntime.get(context, original.id)
            assertEquals(account, retained.account)
            assertEquals(pending, retained.records.snapshot("progress", "book"))
            assertEquals(assignments, LocalLibraryPreferences(original.id, json).getBookshelfAssignments())
            assertBytes(retained)
            val stableConfig = NetworkStoragePreferences(original.id).configuration
            val stableLibrary = LocalLibraryPreferences(original.id, json).getConfig()
            for ((endpoint, expectedRoot) in listOf("root-b" to rootB, "alias-a" to rootA)) {
                saveNetworkLibraryDraft(
                    context,
                    original.id,
                    NetworkStorageDraft(stableConfig.copy(address = "$base/$endpoint/"), "root", "fixture-179"),
                    stableConfig,
                    stableLibrary,
                    assignments,
                    false,
                    onSaved = { savedId = it },
                )
                owned += savedId
                assertNotEquals(original.id, savedId)
                assertEquals(expectedRoot, NetworkStoragePreferences(savedId).configuration.rootIdentity)
                val replacement = NetworkStorageRuntime.get(context, savedId)
                assertEquals(null, replacement.records.snapshot("progress", "book"))
                assertTrue(LocalLibraryPreferences(savedId, json).getBookshelfAssignments().isEmpty())
                assertTrue(LocalLibraryPreferences(savedId, json).getConfig().detachedRoots.isEmpty())
                assertBytes(replacement)
                // Root IDs identify storage, not accounts; alias-a must still get an isolated connection.
                assertEquals(stableConfig, NetworkStoragePreferences(original.id).configuration)
                assertEquals(stableLibrary, LocalLibraryPreferences(original.id, json).getConfig())
                assertEquals(assignments, LocalLibraryPreferences(original.id, json).getBookshelfAssignments())
                assertEquals(
                    pending,
                    NetworkStorageRuntime.get(context, original.id).records.snapshot("progress", "book"),
                )
            }
            assertEquals(3, owned.size)
            assertTrue(manager.profiles().containsAll(inventory))
        } finally {
            manager.profiles().filter { it.name.startsWith(prefix) }.forEach { owned += it.id }
            for (id in owned) {
                check(manager.profiles().singleOrNull { it.id == id }?.name?.startsWith(prefix) != false)
                manager.remove(id).getOrThrow()
                repository.removeConnection(id)
            }
            if (hadActive) preferences.activeConnectionId.set(active) else preferences.activeConnectionId.delete()
        }
    }
}
