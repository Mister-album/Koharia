package tachiyomi.domain.storage.service

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class StorageManagerDownloadDirectoryTest {
    private val context = mockk<Context>()
    private val preference = mockk<Preference<String>>()
    private val preferences = mockk<StoragePreferences> {
        every { baseStorageDirectory } returns preference
    }
    private val clock = AtomicLong(20_000)
    private val changes = Channel<String>(Channel.UNLIMITED)
    private val observing = CompletableDeferred<Unit>()
    private lateinit var root: UniFile
    private lateinit var directory: UniFile

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class, UniFile::class)
        directory = mockk()
        root = root("first", directory)
        every { preference.get() } returns "first"
        every { preference.changes() } returns flow {
            emit("first")
            observing.complete(Unit)
            for (value in changes) emit(value)
        }
    }

    @AfterEach
    fun tearDown() {
        changes.close()
        unmockkStatic(Uri::class, UniFile::class)
    }

    @Test
    fun `SAF reads share resolution but writes still check the directory`() {
        val manager = manager()
        repeat(64) { assertSame(directory, manager.getDownloadsDirectoryForRead()) }
        verify(exactly = 1) { root.createDirectory("downloads") }

        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        assertSame(recreated, manager.getDownloadsDirectory())
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `read hits do not extend the ten second lifetime`() {
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        clock.addAndGet(9_999)
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        clock.incrementAndGet()
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `failed resolution is retried and explicit write failure removes a stale read result`() {
        val manager = manager()
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns directory
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectory())
        assertNull(manager.getDownloadsDirectoryForRead())
        verify(exactly = 4) { root.createDirectory("downloads") }
    }

    @Test
    fun `native file roots retain immediate directory checks`() {
        every { root.uri.scheme } returns "file"
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `a storage change cannot reuse the previous root`() = runBlocking {
        val secondDirectory = mockk<UniFile> {
            every { exists() } returns false
        }
        root("second", secondDirectory)
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        withTimeout(5_000) { observing.await() }
        changes.send("second")
        withTimeout(5_000) {
            while (manager.getDownloadsDirectoryForRead() !== secondDirectory) delay(1)
        }
        assertSame(secondDirectory, manager.getDownloadsDirectoryForRead())
    }

    @Test
    fun `concurrent cold reads resolve SAF once`() {
        val manager = manager()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { root.createDirectory("downloads") } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            directory
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val first = executor.submit<UniFile?> { manager.getDownloadsDirectoryForRead() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val rest = List(7) { executor.submit<UniFile?> { manager.getDownloadsDirectoryForRead() } }
            release.countDown()
            (rest + first).forEach { assertSame(directory, it.get(5, TimeUnit.SECONDS)) }
            verify(exactly = 1) { root.createDirectory("downloads") }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `explicit invalidation forces fresh resolution`() {
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        manager.invalidateDownloadsDirectoryCache()
        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    private fun manager() = StorageManager(context, preferences, clock::get)

    private fun root(name: String, downloads: UniFile): UniFile {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        every { Uri.parse(name) } returns uri
        val root = mockk<UniFile> {
            every { this@mockk.uri } returns uri
            every { exists() } returns true
            every { createDirectory(any()) } returns null
            every { createDirectory("downloads") } returns downloads
        }
        every { UniFile.fromUri(context, uri) } returns root
        return root
    }
}
