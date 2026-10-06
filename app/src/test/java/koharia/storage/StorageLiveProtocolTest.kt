package koharia.storage

import koharia.source.local.createStorageDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Opt-in isolated loopback fixtures; never point these mutation tests at user libraries. */
class StorageLiveProtocolTest {
    @Test fun `WebDAV real server read write range and rename`() = runBlocking {
        assumeTrue(System.getenv("KOHARIA_STORAGE_FIXTURES") == "1")
        exercise(WebDavStorageBackend(OkHttpClient(), "http://127.0.0.1:18765/", "", ""))
    }

    @Test fun `SMB2 real server read write range and rename`() = runBlocking {
        assumeTrue(System.getenv("KOHARIA_STORAGE_FIXTURES") == "1")
        exercise(SmbStorageBackend("smb://127.0.0.1:18445/library", "", "", ""))
    }

    private suspend fun exercise(backend: LibraryStorageBackend) {
        backend.use {
            val directory = "contract-${UUID.randomUUID()}"
            createStorageDirectory(backend, "", directory)
            try {
                assertTrue(browseStorageDirectories(backend, "").any { it.path == directory })
                assertTrue(browseStorageDirectories(backend, directory).isEmpty())
                val path = "$directory/中文 空格 & # %.cbz"
                val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
                backend.write(path, bytes.inputStream(), bytes.size.toLong())
                val entry = backend.stat(path)
                assertTrue(browseStorageDirectories(backend, directory).isEmpty())
                assertEquals(bytes.size.toLong(), entry.size)
                assertEquals(entry.version, backend.list(directory).single().version)
                assertArrayEquals(bytes.copyOfRange(990000, 991024), backend.read(entry, 990000, 1024))
                assertArrayEquals(bytes.takeLast(64).toByteArray(), backend.read(entry, entry.size - 64, 64))
                assertThrows(StorageFailure::class.java) {
                    runBlocking { backend.write(path, bytes.inputStream(), bytes.size.toLong()) }
                }
                val moved = "$directory/renamed.cbz"
                backend.move(entry, moved)
                assertEquals(listOf(moved), backend.list(directory).map { it.path })
                assertArrayEquals(bytes.copyOfRange(10, 30), backend.read(backend.stat(moved), 10, 20))
                backend.delete(backend.stat(moved))
                assertTrue(backend.list(directory).isEmpty())
            } finally {
                // The randomly named fixture directory is owned solely by this test.
                backend.list(directory).forEach { backend.delete(it) }
                backend.delete(backend.stat(directory))
            }
        }
    }
}
