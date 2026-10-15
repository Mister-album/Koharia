package koharia.epub.cache

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.io.File
import java.nio.file.Path

class EpubCacheLeaseTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `clearing keeps a complete book until its last reader releases it`() = withManager { manager, root ->
        val book = File(root, "books/41/fixture.epub").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("owned complete book")
        }
        val manual = File(directory.toFile(), "manual-download.epub").apply { writeText("manual download") }
        manager.acquire(book)
        manager.acquire(book)
        manager.clearServer(41)
        manager.release(book)
        manager.clearServer(41)
        assertTrue(book.isFile, "A second reader still owns the complete cache file")
        manager.release(book)
        manager.clearServer(41)
        assertFalse(book.exists())
        assertTrue(manual.readText() == "manual download")
    }

    @Test
    fun `clearing keeps publication resources until their last reader releases them`() = withManager { manager, _ ->
        val bytes = "owned chapter".encodeToByteArray()
        manager.putResource(41, "fixture", "https://fixture.invalid/chapter", "application/xhtml+xml", bytes)
        manager.acquirePublication(41, "fixture")
        manager.acquirePublication(41, "fixture")
        manager.clearServer(41)
        manager.releasePublication(41, "fixture")
        manager.clearServer(41)
        val retained = manager.getResource(41, "fixture", "https://fixture.invalid/chapter")
        assertNotNull(retained, "A second reader still owns this publication")
        assertArrayEquals(bytes, retained!!.bytes)
        manager.releasePublication(41, "fixture")
        manager.clearServer(41)
        assertTrue(manager.getResource(41, "fixture", "https://fixture.invalid/chapter") == null)
    }

    @Test
    fun `deferred file deletion rechecks a newly acquired reader`() = withManager { manager, root ->
        val book = File(root, "books/41/fixture.epub").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("owned complete book")
        }
        manager.acquire(book)
        manager.clear()
        withCleanupBlocked(manager) {
            manager.release(book)
            manager.acquire(book)
        }
        delay(100)
        manager.clearServer(41)
        assertTrue(book.isFile)
        manager.release(book)
        withTimeout(5_000) { while (book.exists()) delay(10) }
    }

    @Test
    fun `deferred publication deletion rechecks a newly acquired reader`() = withManager { manager, _ ->
        val bytes = "owned chapter".encodeToByteArray()
        manager.putResource(41, "fixture", "https://fixture.invalid/chapter", "text/plain", bytes)
        manager.acquirePublication(41, "fixture")
        manager.clear()
        withCleanupBlocked(manager) {
            manager.releasePublication(41, "fixture")
            manager.acquirePublication(41, "fixture")
        }
        delay(100)
        manager.clearServer(41)
        assertNotNull(manager.getResource(41, "fixture", "https://fixture.invalid/chapter"))
        manager.releasePublication(41, "fixture")
        withTimeout(5_000) {
            while (manager.getResource(41, "fixture", "https://fixture.invalid/chapter") != null) delay(10)
        }
    }

    private suspend fun withCleanupBlocked(manager: EpubCacheManager, block: () -> Unit) {
        val mutex = EpubCacheManager::class.java.getDeclaredField("ioMutex").apply { isAccessible = true }
            .get(manager) as Mutex
        mutex.lock()
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private fun withManager(block: suspend (EpubCacheManager, File) -> Unit) = runBlocking {
        mockkObject(LocalTempCacheDirectoryProvider)
        var manager: EpubCacheManager? = null
        try {
            val root = File(directory.toFile(), "cache").apply { mkdirs() }
            every { LocalTempCacheDirectoryProvider.epubCacheDir(any()) } returns root
            val preferences = mockk<EpubCachePreferences>()
            every { preferences.cacheSizeMb.get() } returns 512
            manager = EpubCacheManager(mockk<Application>(), preferences)
            block(manager, root)
        } finally {
            manager?.let {
                val scope = EpubCacheManager::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
                    .get(it) as CoroutineScope
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
            unmockkObject(LocalTempCacheDirectoryProvider)
        }
    }
}
