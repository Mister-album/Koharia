package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class KavitaBookmarksTest {
    private val repository = MemoryKavitaRepository()
    private val draft = KavitaBookmarkState(KavitaChapterRef(1, 2, 3, 4, 1), KavitaBookmarkKind.IMAGE, 2)
    private var remote = emptyList<JsonObject>()
    private var creates = 0
    private var deletes = 0
    private var failResponse = false
    private var afterWrite: (() -> Unit)? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.close()
            var status = 200
            val body = when {
                exchange.requestURI.path.endsWith("authenticate") ||
                    exchange.requestURI.path.endsWith("refresh-account") ->
                    """{"id":1,"username":"fixture","token":"fixture","roles":["Login","Bookmark"],"kavitaVersion":"0.9.1.4"}"""
                exchange.requestURI.path.endsWith("/bookmark") -> {
                    creates++
                    remote = listOf(draft.body())
                    afterWrite?.invoke()
                    if (failResponse) status = 500
                    ""
                }
                exchange.requestURI.path.endsWith("/unbookmark") || exchange.requestMethod == "DELETE" -> {
                    deletes++
                    remote = emptyList()
                    ""
                }
                else -> JsonArray(remote).toString()
            }.toByteArray()
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val api = KavitaApiClient(
        okhttp3.OkHttpClient(),
        "http://127.0.0.1:${server.address.port}",
        "fixture",
        "fixture",
    )
    private fun coordinator(account: String = "one") = KavitaBookmarks(
        1,
        account,
        repository,
        KavitaCatalog(1, account, repository, api) {},
        CoroutineScope(Dispatchers.IO + Job().apply { cancel() }),
        {},
        { true },
    )

    @AfterEach fun close() {
        api.close()
        server.stop(0)
    }

    @Test fun uncertainCreationIsReconciledAndAnotherAccountCannotSeeIt() = runBlocking {
        val first = coordinator()
        first.set(draft)
        failResponse = true
        assertTrue(runCatching { first.flush() }.isFailure)
        coordinator().flush()
        assertEquals(1, creates)
        assertFalse(repository.operations(1, "one").single().pending)
        assertTrue(repository.operations(1, "other").isEmpty())
    }

    @Test fun lateAcknowledgementDoesNotEraseAQueuedRemoval() = runBlocking {
        val coordinator = coordinator()
        coordinator.set(draft)
        afterWrite = { runBlocking { coordinator.set(draft.copy(desired = false)) } }
        coordinator.flush()
        assertTrue(repository.operations(1, "one").single().pending)
        afterWrite = null
        coordinator.flush()
        assertFalse(repository.operations(1, "one").single().pending)
        assertTrue(coordinator.entries(draft.ref, draft.kind).isEmpty())
    }

    @Test fun restoredActionsWaitForConfirmationAndKeepDeletionsVisible() = runBlocking {
        val coordinator = coordinator()
        coordinator.set(draft.copy(needsConfirmation = true, desired = false))
        coordinator.flush()
        assertEquals(0, creates)
        assertTrue(repository.operations(1, "one").single().pending)
        val entry = coordinator.entries(draft.ref, draft.kind).single()
        assertEquals("true", entry["deleted"].toString())
        assertEquals("true", entry["confirmation"].toString())
        coordinator.set(draft)
        coordinator.flush()
        assertEquals(1, creates)
        assertFalse(repository.operations(1, "one").single().pending)
    }

    @Test fun deletingADirectoryEntryMovedOnAnotherDeviceRequiresReview() = runBlocking {
        val coordinator = coordinator()
        val toc = draft.copy(kind = KavitaBookmarkKind.TOC, title = "Saved passage", anchor = "//p[1]")
        remote = listOf(toc.copy(anchor = "//p[2]").body())
        coordinator.set(toc.copy(desired = false))
        val failure = runCatching { coordinator.flush() }.exceptionOrNull() as KavitaException
        assertEquals(KavitaException.Reason.CONFLICT, failure.reason)
        assertEquals(0, deletes)
        assertTrue(repository.operations(1, "one").single().pending)
        coordinator.discard(toc.key, 1)
        assertFalse(repository.operations(1, "one").single().pending)
        assertEquals("//p[2]", coordinator.entries(toc.ref, toc.kind).single().textValue("bookScrollId"))
    }

    @Test fun discardingAnOlderRevisionDoesNotCancelANewerBookmarkEdit() = runBlocking {
        val coordinator = coordinator()
        coordinator.set(draft)
        coordinator.set(draft.copy(desired = false))
        val failure = runCatching { coordinator.discard(draft.key, 1) }.exceptionOrNull() as KavitaException
        assertEquals(KavitaException.Reason.CONFLICT, failure.reason)
        assertTrue(repository.operations(1, "one").single().pending)
        coordinator.discard(draft.key, 2)
        coordinator.flush()
        assertEquals(0, creates)
        assertEquals(0, deletes)
    }
}
