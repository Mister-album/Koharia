package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.ConnectionSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadProviderTest {
    private val root = directory("root")
    private val storage = mockk<StorageManager> {
        every { getDownloadsDirectory() } returns root
        every { getDownloadsDirectoryForRead() } returns root
        every { invalidateDownloadsDirectoryCache() } returns Unit
    }
    private val connections = mockk<ConnectionPreferences>()
    private val registry = mockk<ConnectionRegistry>()
    private val provider = DownloadProvider(mockk(), storage, mockk(), connections, registry)

    @Test
    fun `unavailable storage does not instantiate detached sources`() {
        every { storage.getDownloadsDirectoryForRead() } returns null
        assertTrue(provider.findSourceDirs(mockk()).isEmpty())
        assertTrue(provider.findOwnedSourceDirs(mockk()).isEmpty())
        verify(exactly = 0) { registry.createSource(any()) }
    }

    @Test
    fun `owned directory discovery resolves a detached adapter only once`() {
        val stub = mockk<Source> {
            every { id } returns 42L
            every { name } returns "Stored connection"
        }
        val adapter = source("current", "shared", owned = setOf("current"))
        val current = directory("current")
        every { connections.providerIdForSource(42L) } returns "komga"
        every { registry.createSource(any()) } returns adapter
        every { root.listFiles() } returns arrayOf(current, directory("shared"))

        assertEquals(listOf(current), provider.findOwnedSourceDirs(stub))

        verify(exactly = 1) { registry.createSource(any()) }
        verify(exactly = 1) { adapter.downloadDirectoryNames() }
    }

    @Test
    fun `cached source remains available while another directory lookup is blocked`() {
        val cached = source("cached")
        val slow = source("slow", "legacy")
        val legacy = directory("legacy")
        val cachedDir = directory("cached")
        every { root.listFiles() } returns arrayOf(cachedDir, legacy)
        assertEquals(listOf(cachedDir), provider.findSourceDirs(cached))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { legacy.renameTo("slow") } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            false
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val loading = executor.submit<List<UniFile>> { provider.findSourceDirs(slow) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val hit = executor.submit<List<UniFile>> { provider.findSourceDirs(cached) }
            assertEquals(listOf(cachedDir), hit.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(listOf(legacy), loading.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `source lookup lists the root once and caches absence until invalidated`() {
        val source = source("current", "legacy", "shared", owned = setOf("current", "legacy"))
        every { root.listFiles() } returns emptyArray()
        repeat(2) { assertEquals(emptyList<UniFile>(), provider.findSourceDirs(source)) }
        verify(exactly = 1) { root.listFiles() }
        val created = directory("current")
        every { root.listFiles() } returns arrayOf(created)
        provider.invalidateSourceDirCache()
        assertEquals(listOf(created), provider.findSourceDirs(source))
    }

    @Test
    fun `shared fallback remains readable but is never renamed as an owned legacy directory`() {
        val source = source("current", "shared", owned = setOf("current"))
        val shared = directory("shared")
        every { root.listFiles() } returns arrayOf(shared)
        every { shared.renameTo(any()) } returns false
        assertEquals(listOf(shared), provider.findSourceDirs(source))
        verify(exactly = 0) { shared.renameTo(any()) }
    }

    @Test
    fun `owned legacy directory migrates even when a shared fallback also exists`() {
        val source = source("current", "legacy", "shared", owned = setOf("current", "legacy"))
        val legacy = directory("legacy")
        val shared = directory("shared")
        val current = directory("current")
        every { root.listFiles() } returns arrayOf(legacy, shared)
        every { root.findFile("current") } returns current
        every { root.findFile("legacy") } returns null
        every { root.findFile("shared") } returns shared
        every { legacy.renameTo("current") } returns true
        assertEquals(listOf(current, shared), provider.findSourceDirs(source))
        verify(exactly = 1) { legacy.renameTo("current") }
        verify(exactly = 0) { shared.renameTo(any()) }
    }

    @Test
    fun `ambiguous owned aliases are retained without choosing a migration target`() {
        val source = source("current", "legacy1", "legacy2")
        val first = directory("legacy1")
        val second = directory("legacy2")
        every { root.listFiles() } returns arrayOf(first, second)
        assertEquals(listOf(first, second), provider.findSourceDirs(source))
        verify(exactly = 0) { first.renameTo(any()) }
        verify(exactly = 0) { second.renameTo(any()) }
    }

    @Test
    fun `storage root changes cannot reuse another roots cached result`() {
        val source = source("current")
        val current = directory("current")
        every { root.listFiles() } returns arrayOf(current)
        assertEquals(listOf(current), provider.findSourceDirs(source))
        val otherRoot = directory("other-root")
        every { otherRoot.listFiles() } returns emptyArray()
        every { storage.getDownloadsDirectory() } returns otherRoot
        every { storage.getDownloadsDirectoryForRead() } returns otherRoot
        assertEquals(emptyList<UniFile>(), provider.findSourceDirs(source))
    }

    @Test
    fun `concurrent misses discover a source only once`() {
        val source = source("current")
        val current = directory("current")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { root.listFiles() } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            arrayOf(current)
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val first = executor.submit<List<UniFile>> { provider.findSourceDirs(source) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val others = List(3) { executor.submit<List<UniFile>> { provider.findSourceDirs(source) } }
            release.countDown()
            (others + first).forEach { assertEquals(listOf(current), it.get(5, TimeUnit.SECONDS)) }
            verify(exactly = 1) { root.listFiles() }
            provider.invalidateSourceDirCache()
            assertEquals(listOf(current), provider.findSourceDirs(source))
            verify(exactly = 2) { root.listFiles() }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `different sources share one cold root listing without per-alias queries`() {
        val entries = (0 until 64).map { directory("source-$it") }
        every { root.listFiles() } returns entries.toTypedArray()
        entries.forEachIndexed { index, directory ->
            assertEquals(listOf(directory), provider.findSourceDirs(source("source-$index", "missing-$index")))
        }
        assertTrue(provider.findSourceDirs(source("absent")).isEmpty())
        verify(exactly = 1) { root.listFiles() }
        verify(exactly = 0) { root.findFile(any()) }
    }

    private fun source(vararg names: String, owned: Set<String> = names.toSet()): DownloadSource = mockk {
        every { downloadDirectoryNames() } returns names.toList()
        every { ownedDownloadDirectoryNames() } returns owned
        every { usesSharedDownloadStorage } returns false
    }

    private fun directory(name: String): UniFile {
        val file = mockk<UniFile>()
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://downloads/$name"
        every { uri.scheme } returns "content"
        every { file.uri } returns uri
        every { file.name } returns name
        return file
    }

    private interface DownloadSource : ConnectionSource, ConnectionDownloadStorageAdapter
}
