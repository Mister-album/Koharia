package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StoragePdfProgressTest {
    private val backend = MemoryStorageBackend()
    private val store =
        StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
    private val progress = StorageProgress(backend, store, "installation")
    private val remote = StorageProgressRecord("book", "v1", "remote", 2, 200, page = 1, total = 4)
    private val path = ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-2.json"
    private fun seed(record: StorageProgressRecord = remote) = backend.seed(path, Json.encodeToString(record))

    @Test fun emptyReviewDoesNotGenerateAWriterOrProgressAndNavigationIsPreciselyAcknowledged() = runBlocking {
        val review = checkNotNull(progress.reviewSnapshot("book", "v1"))
        assertNull(review.remote)
        assertNull(review.localProgress)
        assertTrue(store.list("progress").isEmpty())
        assertTrue(store.list("progress-device").isEmpty())
        assertEquals(0, backend.uploads)
        progress.record("book", "v1", 300, page = 1, total = 4)
        assertFalse(progress.isPageConfirmed("book", "v1", 1, 4, 300))
        progress.flush()
        assertTrue(progress.isPageConfirmed("book", "v1", 1, 4, 300))
        assertFalse(progress.isPageConfirmed("book", "v1", 1, 8, 300))
        assertFalse(progress.isPageConfirmed("book", "v1", 2, 4, 300))
        assertFalse(progress.isPageConfirmed("book", "v2", 1, 4, 300))
        assertFalse(progress.isPageConfirmed("book", "v1", 1, 4, 301))
        val event = checkNotNull(store.get<StoragePendingProgress>("progress", "book"))
        store.put("progress", "book", event.copy(requiresConfirmation = true))
        assertFalse(progress.isPageConfirmed("book", "v1", 1, 4, 300))
        assertNull(progress.reviewSnapshot("book", "v1"))
        progress.record("book", "v1", 400, page = 2, total = 4)
        progress.flush()
        assertEquals(1, backend.uploads)
        assertTrue(checkNotNull(store.get<StoragePendingProgress>("progress", "book")).requiresConfirmation)
    }

    @Test fun acceptingAnActualRemotePageUsesComparedStateWithoutCreatingAnUpload() = runBlocking {
        val local = progress.record("book", "v1", 900, page = 2, total = 4)
        seed()
        val review = checkNotNull(progress.reviewSnapshot("book", "v1"))
        assertEquals(StoragePendingProgress(local, true), review.localProgress)
        assertEquals(local, progress.cached("book"))
        progress.acceptRemotePage("book", "v1", 1, 4, 200, review) {}
        assertEquals(StoragePendingProgress(remote, false), store.get<StoragePendingProgress>("progress", "book"))
        progress.flush()
        assertEquals(0, backend.uploads)
        assertTrue(progress.isPageConfirmed("book", "v1", 1, 4, 200))
        val next = progress.record("book", "v1", 1_000, page = 3, total = 4, completed = true)
        assertEquals(local.device, next.device)
        assertTrue(next.device != remote.device && next.sequence > remote.sequence)
    }

    @Test fun completedRemoteWithoutPageUsesOnlyTheOriginalLastPage() = runBlocking {
        seed(remote.copy(page = null, completed = true))
        val review = progress.reviewSnapshot("book", "v1")
        assertTrue(runCatching { progress.acceptRemotePage("book", "v1", 0, 4, 200, review) {} }.isFailure)
        progress.acceptRemotePage("book", "v1", 3, 4, 200, review) {}
        assertTrue(progress.isPageConfirmed("book", "v1", 3, 4, 200))
        assertFalse(progress.isPageConfirmed("book", "v1", 7, 8, 200))
        assertEquals(0, backend.uploads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["page", "total", "time", "version", "epub", "missing"])
    fun remoteChoiceRejectsMismatchedOriginalPageEvidence(change: String) = runBlocking {
        val old = progress.record("book", "v1", 900, page = 2, total = 4)
        if (change != "missing") seed(if (change == "epub") remote.copy(locator = "epub") else remote)
        val review = progress.reviewSnapshot("book", "v1")
        val result = runCatching {
            progress.acceptRemotePage(
                "book",
                if (change == "version") "v2" else "v1",
                if (change == "page") 2 else 1,
                if (change == "total") 8 else 4,
                if (change == "time") 201 else 200,
                review,
            ) {}
        }
        assertTrue(result.exceptionOrNull() is StorageFailure)
        assertEquals(old, progress.cached("book"))
        assertEquals(0, backend.uploads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["pdf", "epub"])
    fun explicitLocalChoiceKeepsWriterAndWaitsForTheExistingFlush(format: String) = runBlocking {
        val local = progress.record("book", "v1", 900, page = 2, total = 4)
        seed(if (format == "epub") remote.copy(page = null, total = 0, locator = "cloud") else remote)
        val review = progress.reviewSnapshot("book", "v1")
        val result = if (format == "epub") {
            progress.confirmLocal("book", "v1", 1_000, review, {}, locator = "local")
        } else {
            progress.confirmLocal("book", "v1", 1_000, review, {}, page = 2, total = 4)
        }
        assertEquals(local.device, result.device)
        assertTrue(result.sequence > local.sequence)
        assertEquals(0, backend.uploads)
        assertEquals(StoragePendingProgress(result, true), store.get<StoragePendingProgress>("progress", "book"))
        progress.flush()
        assertEquals(1, backend.uploads)
        if (format == "pdf") {
            assertTrue(progress.isPageConfirmed("book", "v1", 2, 4, 1_000))
        } else {
            assertTrue(progress.isEpubConfirmed("book", "v1", "local", 1_000))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["later-local", "remote", "held", "version", "total", "paused", "owner"])
    fun localChoiceAlsoRejectsObsoleteOrBlockedComparisons(change: String) = runBlocking {
        val old = progress.record("book", "v1", 900, page = 2, total = 4)
        seed()
        val review = progress.reviewSnapshot("book", "v1")
        when (change) {
            "later-local" -> progress.record("book", "v1", 950, page = 3, total = 4)
            "remote" -> seed(remote.copy(sequence = 3))
            "held" -> store.put("progress", "book", StoragePendingProgress(old, true, true))
        }
        val before = store.snapshot("progress", "book")
        val receiver = if (change == "owner") StorageProgress(backend, store, "installation") else progress
        val result = runCatching {
            receiver.confirmLocal(
                "book",
                if (change == "version") "v2" else "v1",
                1_000,
                review,
                { if (change == "paused") throw koharia.connection.ConnectionWritesPaused() },
                page = 2,
                total = if (change == "total") 8 else 4,
            )
        }
        assertTrue(result.isFailure)
        assertEquals(before, store.snapshot("progress", "book"))
        assertEquals(0, backend.uploads)
    }
}
