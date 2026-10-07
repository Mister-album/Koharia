package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream

class StorageIdentityTest {
    @Test fun `move identities survive another device and a second rename`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.seed("first.cbz", "content")
        fun records(id: Long) = StorageRecordStore(
            StorageSession(id, "account", "root") { true },
            MemoryStorageRepository(),
            Json,
        )
        val firstRecords = records(1)
        val first = StorageResourceIdentities(backend, firstRecords)
        val original = backend.stat("first.cbz")
        val identity = first.identity(original)
        StorageMutations(backend, firstRecords, first).move(original, "second.cbz")
        val otherRecords = records(2)
        val other = StorageResourceIdentities(backend, otherRecords)
        other.refresh()
        assertEquals(identity, other.identity(backend.stat("second.cbz")))
        StorageMutations(backend, otherRecords, other).move(backend.stat("second.cbz"), "third.cbz")
        first.refresh()
        assertEquals(identity, first.identity(backend.stat("third.cbz")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reusing a moved path creates distinct identities across devices and repeated moves`(
        nativeIdentity: Boolean,
    ) = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("first.cbz", "original") }
        val records = records(1)
        val identities = StorageResourceIdentities(backend, records)
        val original = backend.stat("first.cbz").copy(identity = if (nativeIdentity) "original-object" else null)
        val originalKey = identities.identity(original)
        val mutations = StorageMutations(backend, records, identities)
        mutations.move(original, "second.cbz")
        backend.seed("first.cbz", "replacement")
        val replacement = backend.stat("first.cbz").copy(identity = if (nativeIdentity) "replacement-object" else null)
        val replacementKey = identities.identity(replacement)
        assertNotEquals(originalKey, replacementKey)
        assertEquals(originalKey, identities.identity(backend.stat("second.cbz")))
        assertEquals(replacementKey, identities.identity(replacement.copy(version = "edited")))

        val otherRecords = records(2)
        val other = StorageResourceIdentities(backend, otherRecords)
        other.refresh()
        assertEquals(replacementKey, other.identity(replacement))
        assertEquals(originalKey, other.identity(backend.stat("second.cbz")))
        other.refresh()
        assertEquals(replacementKey, other.identity(replacement))

        mutations.move(replacement, "third.cbz")
        backend.seed("first.cbz", "third resource")
        val newest = backend.stat("first.cbz").copy(identity = if (nativeIdentity) "newest-object" else null)
        val newestKey = identities.identity(newest)
        assertEquals(3, setOf(originalKey, replacementKey, newestKey).size)
        other.refresh()
        assertEquals(newestKey, other.identity(newest))
        assertEquals(replacementKey, other.identity(backend.stat("third.cbz")))
        assertEquals(originalKey, other.identity(backend.stat("second.cbz")))
    }

    @Test fun `directory move retires the identities of every descendant path`() = runBlocking {
        val memory = MemoryStorageBackend().apply {
            directories += "first"
            seed("first/book.cbz", "original")
        }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun move(entry: StorageEntry, destination: String) {
                if (!entry.directory) return memory.move(entry, destination)
                memory.list(entry.path).forEach {
                    memory.move(it, destination + it.path.removePrefix(entry.path))
                }
                memory.directories.remove(entry.path)
                memory.directories.add(destination)
            }
        }
        val records = records(1)
        val identities = StorageResourceIdentities(backend, records)
        val directoryKey = identities.identity(backend.stat("first"))
        val bookKey = identities.identity(backend.stat("first/book.cbz"))
        StorageMutations(backend, records, identities).move(backend.stat("first"), "second")
        memory.directories += "first"
        memory.seed("first/book.cbz", "replacement")
        val other = StorageResourceIdentities(backend, records(2))
        other.refresh()
        assertEquals(directoryKey, other.identity(backend.stat("second")))
        assertEquals(bookKey, other.identity(backend.stat("second/book.cbz")))
        assertNotEquals(directoryKey, other.identity(backend.stat("first")))
        assertNotEquals(bookKey, other.identity(backend.stat("first/book.cbz")))
    }

    @Test fun `lost identity manifest response recovers a stable replacement identity`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("first.cbz", "original") }
        var loseResponse = true
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                memory.write(path, data, length, expectedVersion)
                if (path.startsWith(".koharia/identities/") && loseResponse) {
                    loseResponse = false
                    throw StorageFailure(StorageFailure.Reason.NETWORK)
                }
            }
        }
        val records = records(1)
        val identities = StorageResourceIdentities(backend, records)
        val original = backend.stat("first.cbz")
        val originalKey = identities.identity(original)
        assertThrows(StorageFailure::class.java) {
            runBlocking { StorageMutations(backend, records, identities).move(original, "second.cbz") }
        }
        val restarted = StorageResourceIdentities(backend, records)
        assertTrue(StorageMutations(backend, records, restarted).reconcile().isEmpty())
        memory.seed("first.cbz", "replacement")
        val replacementKey = restarted.identity(backend.stat("first.cbz"))
        assertNotEquals(originalKey, replacementKey)
        val other = StorageResourceIdentities(backend, records(2))
        other.refresh()
        assertEquals(originalKey, other.identity(backend.stat("second.cbz")))
        assertEquals(replacementKey, other.identity(backend.stat("first.cbz")))
    }

    private fun records(connectionId: Long) = StorageRecordStore(
        StorageSession(connectionId, "account", "root") { true },
        MemoryStorageRepository(),
        Json,
    )
}
