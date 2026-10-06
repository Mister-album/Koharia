package koharia.source.local

import koharia.storage.MemoryStorageBackend
import koharia.storage.StorageFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkInitialDirectoriesTest {
    private val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")

    @Test fun `selected defaults are created linked and reusable`() = runBlocking {
        val backend = MemoryStorageBackend()
        val prepared = prepareInitialNetworkDirectories(backend, config)
        assertEquals(setOf("Comics", "Books"), prepared.roots.map { it.relativePath }.toSet())
        prepared.roots.forEach { assertEquals(config.defaultBookshelfId(it.contentType), it.bookshelfId) }
        assertEquals(prepared, prepareInitialNetworkDirectories(backend, prepared))
        assertEquals(setOf("Comics", "Books"), backend.createdDirectories)
    }

    @Test fun `unchecked types and manually assigned roots are preserved`() = runBlocking {
        val backend = MemoryStorageBackend()
        val manual =
            LocalLibraryRootConfig(
                id = "manual",
                relativePath = "existing",
                contentType = LocalLibraryContentType.COMICS,
            )
        val input = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS), roots = listOf(manual))
        assertEquals(input, prepareInitialNetworkDirectories(backend, input))
        assertTrue(backend.createdDirectories.isEmpty())
    }

    @Test fun `same name file is not overwritten and completed setup is untouched`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.seed("Comics", "preserve")
        assertThrows(StorageFailure::class.java) { runBlocking { prepareInitialNetworkDirectories(backend, config) } }
        assertEquals("preserve", backend.contents.getValue("Comics").decodeToString())
        val completed = config.copy(setupCompleted = true)
        assertEquals(completed, prepareInitialNetworkDirectories(backend, completed))
    }

    @Test fun `retry reuses directories after uncertain mutation without duplication`() = runBlocking {
        val backend = MemoryStorageBackend().apply { failAfterMutation = 1 }
        assertThrows(StorageFailure::class.java) { runBlocking { prepareInitialNetworkDirectories(backend, config) } }
        backend.failAfterMutation = null
        val prepared = prepareInitialNetworkDirectories(backend, config)
        assertEquals(2, prepared.roots.size)
        assertEquals(setOf("Comics", "Books"), backend.createdDirectories)
    }
}
