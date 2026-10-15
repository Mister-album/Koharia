package koharia.storage

import com.hippo.unifile.RemoteStorageFile
import com.hippo.unifile.UniFile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

class StorageReaderSessionTest {
    @Test
    fun `successful empty remote check returns a fresh absence snapshot for both network modes`() = runTest {
        val chapter = Chapter.create().copy(id = 9, mangaId = 4, url = "book")
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            val before = System.currentTimeMillis()
            val result = remoteAdapter(mode) { null }.refreshEpubProgress(4, chapter)
            requireNotNull(result)
            assertEquals(9, result.chapterId)
            assertEquals(4, result.mangaId)
            assertEquals("book", result.bookUrl)
            assertNull(result.locatorJson)
            assertNull(result.modifiedAt)
            assertNull(result.progression)
            assertNull(result.positionIndex)
            assertTrue(result.checkedAt.time in before..System.currentTimeMillis())
        }
    }

    @Test
    fun `local only network root does not claim a remote absence check`() = runTest {
        val adapter = remoteAdapter(LibraryStorageMode.WEBDAV, persistentIdentity = false) { null }
        assertNull(adapter.refreshEpubProgress(4, Chapter.create().copy(url = "book")))
    }

    @Test
    fun `failed remote reads remain failures rather than empty progress`() = runTest {
        for (reason in listOf(
            StorageFailure.Reason.NETWORK,
            StorageFailure.Reason.PERMISSION,
            StorageFailure.Reason.UNVERIFIED,
        )) {
            val failure = StorageFailure(reason)
            val adapter = remoteAdapter(LibraryStorageMode.WEBDAV) { throw failure }
            assertSame(
                failure,
                runCatching { adapter.refreshEpubProgress(4, Chapter.create().copy(url = "book")) }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun `empty remote read from an obsolete owner cannot authorize writes`() = runTest {
        var valid = true
        val adapter = remoteAdapter(LibraryStorageMode.SMB) {
            valid = false
            null
        }
        val chapter = Chapter.create().copy(url = "book")
        val binding = adapter.captureEpubProgressSession(chapter) {
            if (!valid) throw CancellationException("Root changed")
        }
        assertTrue(
            runCatching { binding.adapter.refreshEpubProgress(4, chapter) }.exceptionOrNull() is CancellationException,
        )
    }

    private fun remoteAdapter(
        mode: LibraryStorageMode,
        persistentIdentity: Boolean = true,
        read: suspend () -> StorageProgressRecord?,
    ): StorageReaderProgressAdapter {
        val entry = StorageEntry("book", false, version = "v1")
        val identities = mockk<StorageResourceIdentities>()
        coEvery { identities.identity(entry) } returns "resource"
        val progress = mockk<StorageProgress>()
        coEvery { progress.pull("resource", "v1") } coAnswers { read() }
        val runtime = mockk<NetworkStorageRuntime> {
            every { cached("book") } returns entry
            every { session } returns StorageSession(1, "account", "root") { true }
            every { config } returns NetworkStorageConfiguration(mode, persistentIdentity = persistentIdentity)
            every { this@mockk.identities } returns identities
            every { this@mockk.progress } returns progress
        }
        val file = mockk<RemoteStorageFile> {
            every { this@mockk.runtime } returns runtime
            every { storagePath } returns "book"
        }
        return StorageReaderProgressAdapter { file }
    }

    @Test
    fun `native reader resolves once and rejects a changed root`() = runTest {
        var resolves = 0
        var valid = true
        val adapter = StorageReaderProgressAdapter {
            resolves++
            mockk<UniFile>()
        }
        val binding = adapter.captureEpubProgressSession(Chapter.create().copy(url = "book")) {
            if (!valid) throw CancellationException("Root changed")
        }
        assertNull(binding.adapter.pullEpubProgress("book"))
        val unsupported = runCatching {
            binding.adapter.acceptRemoteEpubProgress("book", mockk(), Date())
        }.exceptionOrNull()
        assertEquals(StorageFailure.Reason.UNSUPPORTED, (unsupported as StorageFailure).reason)
        valid = false
        assertTrue(runCatching { binding.adapter.pullEpubProgress("book") }.exceptionOrNull() is CancellationException)
        assertEquals(1, resolves)
    }

    @Test
    fun `network reader retains opened runtime and rejects its invalidation before writing`() = runTest {
        var active = true
        var resolves = 0
        val runtime = mockk<NetworkStorageRuntime>()
        val entry = StorageEntry("book", false, version = "v1")
        every { runtime.session } returns StorageSession(1, "account", "root") { active }
        every { runtime.cached("book") } returns entry
        val file = mockk<RemoteStorageFile> {
            every { this@mockk.runtime } returns runtime
            every { storagePath } returns "book"
        }
        val binding = StorageReaderProgressAdapter {
            resolves++
            file
        }
            .captureEpubProgressSession(Chapter.create().copy(url = "book")) { }
        active = false
        val failure = runCatching {
            binding.adapter.pushEpubProgress("book", mockk(), emptyList(), Date())
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(
            runCatching {
                binding.adapter.acceptRemoteEpubProgress("book", mockk(), Date())
            }.exceptionOrNull() is CancellationException,
        )
        assertEquals(1, resolves)
        verify(exactly = 0) { runtime.progress }
    }

    @Test
    fun `content replacement cannot receive the old open book progress`() = runTest {
        val runtime = mockk<NetworkStorageRuntime>()
        var entry = StorageEntry("book", false, version = "v1")
        every { runtime.session } returns StorageSession(1, "account", "root") { true }
        every { runtime.cached("book") } answers { entry }
        val file = mockk<RemoteStorageFile> {
            every { this@mockk.runtime } returns runtime
            every { storagePath } returns "book"
        }
        val binding = StorageReaderProgressAdapter { file }
            .captureEpubProgressSession(Chapter.create().copy(url = "book")) { }
        entry = entry.copy(version = "v2")
        val failure = runCatching {
            binding.adapter.pushEpubProgress("book", mockk(), emptyList(), Date())
        }.exceptionOrNull()
        assertEquals(StorageFailure.Reason.CONFLICT, (failure as StorageFailure).reason)
        verify(exactly = 0) { runtime.progress }
    }

    @Test
    fun `missing cached metadata cannot attach a later resource to an open reader`() = runTest {
        val runtime = mockk<NetworkStorageRuntime>()
        var entry: StorageEntry? = null
        every { runtime.session } returns StorageSession(1, "account", "root") { true }
        every { runtime.cached("book") } answers { entry }
        val file = mockk<RemoteStorageFile> {
            every { this@mockk.runtime } returns runtime
            every { storagePath } returns "book"
        }
        val binding = StorageReaderProgressAdapter { file }
            .captureEpubProgressSession(Chapter.create().copy(url = "book")) { }
        assertNull(binding.adapter.pullEpubProgress("book"))
        entry = StorageEntry("book", false, version = "new")
        val failure = runCatching { binding.adapter.pullEpubProgress("book") }.exceptionOrNull()
        assertEquals(StorageFailure.Reason.CONFLICT, (failure as StorageFailure).reason)
        verify(exactly = 0) { runtime.progress }
    }
}
