package koharia.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageRoutingTest {
    @Test fun `LAN network failure falls back but authentication and root errors never do`() = runBlocking {
        val primary = MemoryStorageBackend().apply { seed("book", "primary") }
        val internal = MemoryStorageBackend().apply { seed("book", "internal") }
        for (reason in listOf(StorageFailure.Reason.AUTH, StorageFailure.Reason.UNVERIFIED)) {
            val router = StorageEndpointRouter(primary, internal, { "wifi" }, { backend ->
                if (backend === internal) throw StorageFailure(reason)
            })
            val error = assertThrows(StorageFailure::class.java) { runBlocking { router.stat("book") } }
            assertEquals(reason, error.reason)
        }
        val router = StorageEndpointRouter(primary, internal, { "wifi" }, { backend ->
            if (backend === internal) throw StorageFailure(StorageFailure.Reason.NETWORK)
        })
        val entry = router.stat("book")
        assertEquals("primary", router.read(entry, 0, entry.size.toInt()).decodeToString())
    }

    @Test fun `lost mutation response is never replayed on primary`() = runBlocking {
        val primary = MemoryStorageBackend()
        val internal = MemoryStorageBackend().apply { failAfterMutation = 1 }
        val router = StorageEndpointRouter(primary, internal, { "wifi" }, {})
        assertThrows(StorageFailure::class.java) {
            runBlocking { router.write("book", "content".byteInputStream(), 7) }
        }
        assertEquals(0, primary.uploads)
        assertEquals(1, internal.uploads)
    }
}
