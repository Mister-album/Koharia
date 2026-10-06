package koharia.source.local

import koharia.storage.LibraryStorageBackend
import koharia.storage.MemoryStorageBackend
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import koharia.storage.browseStorageDirectories
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkStorageDirectoryTest {
    @Test fun `creation rejects unsafe names conflicts and denied permissions`() = runBlocking {
        val backend = MemoryStorageBackend()
        for (name in listOf("..", "a/b", ".koharia", " x", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { createStorageDirectory(backend, "", name) }
            }
        }
        createStorageDirectory(backend, "", "中文 空格")
        assertTrue(backend.stat("中文 空格").directory)
        val conflict = assertThrows(StorageFailure::class.java) {
            runBlocking { createStorageDirectory(backend, "", "中文 空格") }
        }
        assertEquals(StorageFailure.Reason.CONFLICT, conflict.reason)
        val readonly = object : LibraryStorageBackend by backend {
            override suspend fun directoryCreationAllowed(path: String) = false
        }
        val denied = assertThrows(StorageFailure::class.java) {
            runBlocking { createStorageDirectory(readonly, "", "denied") }
        }
        assertEquals(StorageFailure.Reason.PERMISSION, denied.reason)
        assertEquals(setOf("中文 空格"), backend.createdDirectories)
    }

    @Test fun `lost create response is not automatically replayed or overwritten`() = runBlocking {
        val backend = MemoryStorageBackend().apply { failAfterMutation = 1 }
        assertThrows(StorageFailure::class.java) { runBlocking { createStorageDirectory(backend, "", "new") } }
        assertEquals(setOf("new"), backend.createdDirectories)
        assertTrue(browseStorageDirectories(backend, "").any { it.name == "new" })
        val retry = assertThrows(StorageFailure::class.java) {
            runBlocking { createStorageDirectory(backend, "", "new") }
        }
        assertEquals(StorageFailure.Reason.CONFLICT, retry.reason)
    }

    @Test fun `browser only exposes direct child folders inside the configured root`() = runBlocking {
        val backend = object : LibraryStorageBackend by MemoryStorageBackend() {
            override suspend fun stat(path: String) = StorageEntry(path, true)
            override suspend fun list(path: String) = listOf(
                StorageEntry("root/book.cbz", false),
                StorageEntry("root/中文 空格", true),
                StorageEntry("root", true),
                StorageEntry("root/.koharia", true),
                StorageEntry("root/sub/deeper", true),
                StorageEntry("../outside", true),
                StorageEntry("outside", true),
                StorageEntry("root/中文 空格", true),
            )
        }
        assertEquals(listOf("root/中文 空格"), browseStorageDirectories(backend, "root").map { it.path })
    }

    @Test fun `empty folder remains selectable and files are never shown`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.createDirectory("empty")
        assertEquals(emptyList<StorageEntry>(), browseStorageDirectories(backend, "empty"))
    }
}
