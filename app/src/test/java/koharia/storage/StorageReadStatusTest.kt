package koharia.storage

import com.hippo.unifile.RemoteStorageFile
import com.hippo.unifile.UniFile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionReadStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.InputStream

class StorageReadStatusTest {
    @Test
    fun `native local and unresolved resources have different outcomes`() = runTest {
        assertEquals(
            ConnectionReadStatus.LOCAL_ONLY,
            StorageReaderProgressAdapter { mockk<UniFile>() }.setChapterReadStatus("native", true),
        )
        assertEquals(
            ConnectionReadStatus.UNAVAILABLE,
            StorageReaderProgressAdapter { null }.setChapterReadStatus("missing", true),
        )
    }

    @ParameterizedTest
    @EnumSource(StorageFailure.Reason::class, names = ["NETWORK", "PERMISSION", "AUTH", "CONFLICT"])
    fun `network storage preserves queued intent and reports upload failures`(
        failure: StorageFailure.Reason,
    ) = runTest {
        val memory = MemoryStorageBackend()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                throw StorageFailure(failure)
            }
        }
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "device")
        val runtime = mockk<NetworkStorageRuntime>()
        val entry = StorageEntry("book", false, version = "v1")
        val identities = mockk<StorageResourceIdentities>()
        every { runtime.cached("book") } returns entry
        every { runtime.progress } returns progress
        every { runtime.identities } returns identities
        coEvery { identities.identity(entry) } returns "book-id"
        val file = mockk<RemoteStorageFile>()
        every { file.runtime } returns runtime
        every { file.storagePath } returns "book"
        val result = StorageReaderProgressAdapter { file }.setChapterReadStatus("book", true)
        assertEquals(
            when (failure) {
                StorageFailure.Reason.PERMISSION, StorageFailure.Reason.AUTH -> ConnectionReadStatus.DENIED
                StorageFailure.Reason.CONFLICT -> ConnectionReadStatus.CONFLICT
                else -> ConnectionReadStatus.PENDING
            },
            result,
        )
        val pending = requireNotNull(store.get<StoragePendingProgress>("progress", "book-id"))
        assertTrue(pending.pending)
        assertTrue(pending.record.completed)
    }
}
