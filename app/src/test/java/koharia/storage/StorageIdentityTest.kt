package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
