package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class KavitaAnnotationCoordinatorTest {
    private val json = Json { encodeDefaults = true }
    private val repository = MemoryKavitaRepository()
    private var remote: KavitaAnnotation? = null
    private var creates = 0
    private var localWritesAllowed = true
    private var likes = 0
    private var loseCreateResponse = false
    private var createActuallySaved = true
    private var duringUpdate: (() -> Unit)? = null
    private val draft = KavitaAnnotation(
        chapterId = 3,
        seriesId = 2,
        volumeId = 4,
        libraryId = 1,
        ownerUserId = 1,
        xPath = "//body/p[1]",
        endingXPath = "//body/p[1]",
        selectedText = "selected text",
    )
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            var status = 200
            val payload = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = when {
                path.endsWith("authenticate") || path.endsWith("refresh-account") ->
                    """{"id":1,"username":"reader","token":"fixture","roles":["Login"],"kavitaVersion":"0.9.1.4"}"""
                path.endsWith("Annotation/create") -> {
                    creates++
                    val created = json.decodeFromString<KavitaAnnotation>(payload).copy(id = 10)
                    if (createActuallySaved) remote = created
                    if (loseCreateResponse) status = 500
                    json.encodeToString(created)
                }
                path.endsWith("Annotation/update") -> {
                    val updated = json.decodeFromString<KavitaAnnotation>(payload)
                    duringUpdate?.invoke()
                    remote = updated
                    json.encodeToString(updated)
                }
                path.endsWith("Annotation/all") -> json.encodeToString(listOfNotNull(remote))
                path.endsWith("Annotation/10") -> {
                    if (remote == null) status = 404
                    json.encodeToString(remote)
                }
                path.endsWith("Annotation/like") -> {
                    likes++
                    "{}"
                }
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }
    private val api =
        KavitaApiClient(okhttp3.OkHttpClient(), "http://127.0.0.1:${server.address.port}", "fixture", "test")
    private fun coordinator(account: String = "one") = KavitaAnnotationCoordinator(
        1,
        account,
        1,
        repository,
        KavitaCatalog(1, account, repository, api) {},
        CoroutineScope(Dispatchers.Default + Job().apply { cancel() }),
        {},
        { localWritesAllowed },
    )

    @AfterEach fun close() {
        api.close()
        server.stop(0)
    }

    @Test fun lostCreateResponseIsReconciledAfterRestartWithoutDuplicateCreation() = runBlocking {
        val first = coordinator()
        val key = first.create(draft, """{"href":"page-0.xhtml"}""")
        loseCreateResponse = true
        assertTrue(runCatching { first.flush() }.isFailure)
        val restarted = coordinator()
        restarted.flush()
        assertEquals(1, creates)
        val saved = restarted.cached(3).single()
        assertEquals(key, saved.entry.key)
        assertEquals(10L, saved.entry.remoteId)
        assertFalse(saved.entry.pending)
        assertTrue(coordinator("other").cached(3).isEmpty())
    }

    @Test fun replacementContentKeepsOldTextAndRequiresANewSelectionBeforeUploading() = runBlocking {
        val coordinator = coordinator()
        val key = coordinator.create(draft.withPlainComment("Preserved note"), null)
        coordinator.contentChanged(3)
        coordinator.flush()
        assertEquals(0, creates)
        val old = coordinator.cached(3).single()
        assertTrue(old.state.anchorStale)
        assertTrue(old.state.conflict)
        coordinator.reanchor(key, old.entry.revision, 1, "//body/p[2]", "//body/p[2]", "New selection", "{}")
        coordinator.flush()
        assertEquals(1, creates)
        val records = coordinator.cached(3)
        assertEquals(2, records.size)
        assertTrue(records.any { it.state.anchorStale && it.state.annotation.selectedText == draft.selectedText })
        assertEquals("Preserved note", records.single { !it.state.anchorStale }.state.annotation.commentPlainText)
    }

    @Test fun uncertainCreateWithoutVisibleResultRequiresAnExplicitDecision() = runBlocking {
        val coordinator = coordinator()
        coordinator.create(draft, null)
        loseCreateResponse = true
        createActuallySaved = false
        assertTrue(runCatching { coordinator.flush() }.isFailure)
        coordinator.flush()
        coordinator.flush()
        assertEquals(1, creates)
        val saved = coordinator.cached(3).single()
        assertTrue(saved.entry.pending)
        assertTrue(saved.state.conflict)
        assertEquals(draft.selectedText, saved.state.annotation.selectedText)
    }

    @Test fun refreshingReplacedContentPreservesOriginalNoteEvenAfterRemoteEditAndDeletion() = runBlocking {
        val coordinator = coordinator()
        coordinator.create(draft.withPlainComment("Original note"), null)
        coordinator.flush()
        coordinator.contentChanged(3)
        remote = remote!!.withPlainComment("Remote edit")
        val conflicted = coordinator.load(3, true).single()
        assertEquals("Original note", conflicted.state.annotation.commentPlainText)
        assertEquals("Remote edit", conflicted.state.remoteConflict?.commentPlainText)
        assertTrue(conflicted.state.anchorStale)
        remote = null
        val retained = coordinator.load(3, true).single()
        assertEquals("Original note", retained.state.annotation.commentPlainText)
        assertTrue(retained.state.conflict)
        assertFalse(retained.state.deleted)
    }

    @Test fun privacyModePreventsLikeRequests() = runBlocking {
        localWritesAllowed = false
        val failure = runCatching { coordinator().like(10, true) }.exceptionOrNull() as KavitaException
        assertEquals(KavitaException.Reason.PERMISSION, failure.reason)
        assertEquals(0, likes)
    }

    @Test fun lateUpdateCannotEraseANewerLocalEdit() = runBlocking {
        val coordinator = coordinator()
        val key = coordinator.create(draft, null)
        coordinator.flush()
        coordinator.edit(key, 1, "first", 1, false)
        duringUpdate = { runBlocking { coordinator.edit(key, 2, "newer", 2, true) } }
        coordinator.flush()
        val newer = coordinator.cached(3).single()
        assertTrue(newer.entry.pending)
        assertEquals("newer", newer.state.annotation.commentPlainText)
        assertEquals("first", newer.state.baseline?.commentPlainText)
        duringUpdate = null
        coordinator.flush()
        assertFalse(coordinator.cached(3).single().entry.pending)
        assertEquals("newer", remote?.commentPlainText)
    }

    @Test fun conflictsRetainBothVersionsAndCanKeepBoth() = runBlocking {
        val coordinator = coordinator()
        val key = coordinator.create(draft, null)
        coordinator.flush()
        coordinator.edit(key, 1, "local", 0, false)
        remote = remote!!.copy(comment = "remote", commentHtml = "remote", commentPlainText = "remote")
        coordinator.flush()
        val conflicted = coordinator.cached(3).single()
        assertTrue(conflicted.state.conflict)
        assertEquals("local", conflicted.state.annotation.commentPlainText)
        assertEquals("remote", conflicted.state.remoteConflict?.commentPlainText)
        coordinator.resolve(key, 2, KavitaAnnotationResolution.BOTH)
        val both = coordinator.cached(3)
        assertEquals(setOf("local", "remote"), both.map { it.state.annotation.commentPlainText }.toSet())
        assertEquals(1, both.count { it.entry.pending })
    }
}
