package koharia.epub

import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.epub.cache.EpubCacheManager
import koharia.epub.service.EpubReaderSupportResolver
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaEpubPublicationService
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KavitaEpubLayoutProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fontChangeKeepsServerResourceAndAnchorIndependentOfVisualPages(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val cache = Injekt.get<EpubCacheManager>()
        val progress = Injekt.get<EpubProgressRepository>()
        val shared = Injekt.get<SharedAppPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.cacheDir, "kavita-layout-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val profile = profiles.add("kavita", "Layout progress ${UUID.randomUUID()}")
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("fixture_owned_profile", profile.id)
                putString("fixture_owned_root", root.name)
                putLong("fixture_previous_active", connections.activeConnectionId.get())
            },
        )
        try {
            replace(connections.activeConnectionId, ConnectionPreferences.LOCAL_CONNECTION_ID)
            replace(Injekt.get<eu.kanade.domain.base.BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.basePreferences().incognitoMode, false)
            replace(shared.epubReaderPreferences().syncRemoteProgression, true)
            replace(shared.epubReaderPreferences().correctRemoteServerTimestamps, false)
            replace(shared.epubReaderPreferences().persistReaderSettingsChanges, true)
            replace(shared.epubReaderPreferences().completionThresholdPercent, 100)
            replace(shared.downloadPreferences().removeAfterReadSlots, -1)
            val layout = shared.epubLayoutPreferences()
            replace(layout.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
            replace(layout.pageDirection, EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT)
            replace(layout.fontSize, 1.0f)
            replace(layout.publisherStyles, false)
            replace(
                layout.pageTransitionEffect,
                eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect.NONE.value,
            )
            Server().use { server ->
                profiles.commitConfiguration(profile.id) { draft ->
                    KavitaPreferences(profile.id, draft).save(server.address, "fixture-key", server.account)
                }
                val source = Injekt.get<SourceManager>().get(profile.id) as KavitaSource
                val session = source.session()
                val ref = KavitaChapterRef(1, 2, 3, 4, 3)
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = session.identity.series(2),
                            title = "Layout book",
                        ),
                    ),
                ).single()
                val chapter = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = session.identity.chapter(ref),
                            name = "Layout book",
                        ),
                    ),
                ).single()
                val metadata = source.resolvePublication(chapter, allowRemoteLookup = true)
                val cached = checkNotNull(cache.cacheCompleteBook(source, chapter.url, metadata.publicationKey))
                val originalBytes = cached.readBytes()
                val resolution = EpubReaderSupportResolver().resolve(manga.id, chapter.id)
                assertTrue(resolution.isCompleteCache && resolution.isNativeSupported)
                FixtureActivityLauncher.launch<EpubReaderActivity>(
                    EpubReaderActivity.newIntent(context, manga.id, chapter.id, source.id, resolution),
                ).use { scenario ->
                    lateinit var activity: EpubReaderActivity
                    lateinit var model: EpubReaderViewModel
                    scenario.onActivity {
                        activity = it
                        model = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                    }
                    awaitReady(model)
                    suspend fun navigateToResource(page: Int) {
                        withContext(Dispatchers.Main) {
                            val publication = checkNotNull(
                                Injekt.get<EpubReaderSessionRepository>().get(chapter.id),
                            ).publication
                            val link = publication.readingOrder[page]
                            val locator = checkNotNull(publication.locatorFromLink(link)).copy(
                                locations = Locator.Locations(progression = 0.0),
                            )
                            assertTrue(
                                activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                                    .single().goTo(locator),
                            )
                        }
                        awaitCondition("resource-$page-anchor") {
                            val locator = model.currentLocator()
                            locator != null && KavitaEpubPublicationService.pageIndex(
                                locator.href.toString(),
                            ) == page &&
                                anchor(locator).startsWith("//body")
                        }
                    }
                    suspend fun saveAndConfirm() {
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        withTimeout(45_000) {
                            while (session.reading.hasPending(ref.chapterId)) delay(100)
                        }
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        val stored = checkNotNull(progress.getProgress(chapter.id))
                        assertEquals(stored.updatedAt, stored.lastSyncedAt)
                    }
                    navigateToResource(1)
                    saveAndConfirm()
                    awaitCondition("initial-resource-upload") { server.latest.get()?.getInt("pageNum") == 1 }
                    val original = checkNotNull(server.latest.get())
                    assertEquals("//body/h1[1]", original.getString("bookScrollId"))
                    assertEquals(anchor(checkNotNull(model.currentLocator())), original.getString("bookScrollId"))
                    awaitReady(model)
                    val before = model.state.value
                    val beforeTotal = checkNotNull(before.totalVisualPages)
                    val beforeWrites = server.writes.size
                    status("before", before.currentVisualPage, beforeTotal, server)
                    withContext(Dispatchers.Main) {
                        EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                            .fontSize.set(2.0f)
                    }
                    awaitCondition("new-layout-generation") {
                        model.state.value.paginationGeneration > before.paginationGeneration
                    }
                    awaitReady(model)
                    saveAndConfirm()
                    val after = model.state.value
                    val afterTotal = checkNotNull(after.totalVisualPages)
                    assertTrue("Font size must increase actual visual pages", afterTotal > beforeTotal)
                    assertTrue(afterTotal > 3)
                    for (write in server.writes.drop(beforeWrites) + listOf(checkNotNull(server.latest.get()))) {
                        assertEquals(original.getInt("pageNum"), write.getInt("pageNum"))
                        assertEquals(original.getString("bookScrollId"), write.getString("bookScrollId"))
                    }
                    status("layout-kept-resource-anchor", after.currentVisualPage, afterTotal, server)
                    for (page in 1..10) {
                        val previous = checkNotNull(model.currentLocator()).locations.progression
                        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                            instrumentation.sendKeySync(
                                KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                            )
                        }
                        awaitCondition("forward-$page") {
                            model.currentLocator()?.locations?.progression != previous
                        }
                        saveAndConfirm()
                        val uploaded = checkNotNull(server.latest.get())
                        assertEquals(1, uploaded.getInt("pageNum"))
                        assertEquals(anchor(checkNotNull(model.currentLocator())), uploaded.getString("bookScrollId"))
                        if (uploaded.getString("bookScrollId") != original.getString("bookScrollId")) break
                    }
                    assertTrue(
                        checkNotNull(server.latest.get()).getString("bookScrollId") !=
                            original.getString("bookScrollId"),
                    )
                    assertTrue(checkNotNull(server.latest.get()).getString("bookScrollId").startsWith("//body/p["))
                    status("forward-uploaded-dom-anchor", model.state.value.currentVisualPage, afterTotal, server)
                    navigateToResource(2)
                    saveAndConfirm()
                    assertEquals(2, checkNotNull(server.latest.get()).getInt("pageNum"))
                    assertEquals("//body/h1[1]", checkNotNull(server.latest.get()).getString("bookScrollId"))
                    assertEquals(
                        anchor(checkNotNull(model.currentLocator())),
                        checkNotNull(server.latest.get()).getString("bookScrollId"),
                    )
                    assertEquals(chapter.id, model.state.value.chapterId)
                    assertEquals(profile.id, model.state.value.sourceId)
                    val operation = checkNotNull(session.reading.cached(ref.chapterId))
                    assertEquals(ref, operation.ref)
                    assertEquals(3, operation.totalPages)
                    assertTrue(!operation.conflict && !session.reading.hasPending(ref.chapterId))
                    assertTrue(originalBytes.contentEquals(cached.readBytes()))
                    status("third-resource-confirmed", model.state.value.currentVisualPage, afterTotal, server)
                    for (write in server.writes) {
                        assertEquals(1L, write.getLong("libraryId"))
                        assertEquals(2L, write.getLong("seriesId"))
                        assertEquals(3L, write.getLong("volumeId"))
                        assertEquals(4L, write.getLong("chapterId"))
                        assertTrue(write.getInt("pageNum") in 0..2)
                        assertTrue(write.getString("bookScrollId").startsWith("//body"))
                        assertTrue(!write.has("totalVisualPages") && !write.has("currentVisualPage"))
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitCondition("reader-closed") { activity.isDestroyed }
                }
                awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
                instrumentation.sendStatus(
                    2,
                    Bundle().apply { putString("layout_read_only_queries", server.readOnlyQueries.joinToString()) },
                )
                assertTrue(server.unexpectedMutations.joinToString(), server.unexpectedMutations.isEmpty())
                server.failure.get()?.let { throw AssertionError("Fixture server failed", it) }
            }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
                cache.clearServer(profile.id)
            } finally {
                restores.asReversed().forEach { it() }
                check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("layout_cleanup", "owned-profile-cache-root-removed;preferences-restored") },
            )
        }
    }

    private suspend fun awaitReady(model: EpubReaderViewModel) = awaitCondition("pagination-ready") {
        val state = model.state.value
        check(state.errorMessage == null) { state.errorMessage.orEmpty() }
        state.isReady && model.currentLocator() != null &&
            state.paginationPhase in setOf(EpubPaginationPhase.READY, EpubPaginationPhase.CACHED) &&
            (state.totalVisualPages ?: 0) > 0
    }

    private suspend fun awaitCondition(phase: String, condition: () -> Boolean) {
        instrumentation.sendStatus(2, Bundle().apply { putString("layout_wait", phase) })
        withTimeout(45_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(100)
        }
    }

    private fun anchor(locator: Locator) = locator.toJSON().getJSONObject("locations").optString("kavitaXPath")

    private fun status(phase: String, page: Int?, total: Int?, server: Server) {
        val value = checkNotNull(server.latest.get())
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "layout_progress",
                    "$phase;visual=$page/$total;resource=${value.getInt("pageNum")};" +
                        "anchor=${value.getString("bookScrollId")};writes=${server.writes.size}",
                )
            },
        )
    }

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${listener.localPort}/"
        val account = KavitaAccount(1, "fixture", "fixture-token", listOf("Admin"), "0.8.8", "created", "install")
        val writes = CopyOnWriteArrayList<JSONObject>()
        val latest = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val unexpectedMutations = CopyOnWriteArrayList<String>()
        val readOnlyQueries = CopyOnWriteArrayList<String>()
        private val worker = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = BufferedInputStream(socket.getInputStream())
                        fun line(): String = buildString {
                            while (true) {
                                val byte = input.read()
                                if (byte < 0 || byte == 10) break
                                if (byte != 13) append(byte.toChar())
                            }
                        }
                        val request = line().split(' ')
                        if (request.size < 2) return@use
                        val method = request[0]
                        val url = (address.trimEnd('/') + request[1]).toHttpUrl()
                        val path = url.encodedPath
                        val headers = buildMap {
                            while (true) {
                                val header = line()
                                if (header.isBlank()) break
                                put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                            }
                        }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < body.size) {
                            val count = input.read(body, offset, body.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        var code = 200
                        val payload = when {
                            method == "POST" && path == "/api/Reader/progress" -> {
                                val value = JSONObject(body.toString(Charsets.UTF_8))
                                assertEquals(4L, value.getLong("chapterId"))
                                writes += value
                                latest.set(value)
                                "{}"
                            }
                            path == "/api/Account/refresh-account" || path == "/api/Plugin/authenticate" ->
                                """{"id":1,"username":"fixture","token":"fixture-token","roles":["Admin"],
                                    "kavitaVersion":"0.8.8","createdUtc":"created"}"""
                            method == "POST" && path == "/hubs/messages/negotiate" -> {
                                assertTrue(body.isEmpty())
                                readOnlyQueries += "$method $path"
                                code = 404
                                "{}"
                            }
                            method != "GET" -> {
                                unexpectedMutations += "$method $path"
                                code = 405
                                "{}"
                            }
                            path == "/api/Server/server-info-slim" -> """{"installId":"install"}"""
                            path == "/api/Library/libraries" -> """[{"id":1,"name":"Layout","type":2}]"""
                            path == "/api/Chapter" || path == "/api/Series/chapter" ->
                                """{"id":4,"volumeId":3,"title":"Layout","pages":3,"format":3,
                                    "lastModifiedUtc":"2026-01-01T00:00:00Z"}"""
                            path == "/api/Book/4/book-info" -> """{"pages":3,"bookTitle":"Layout"}"""
                            path == "/api/Book/4/chapters" -> "[]"
                            path == "/api/Book/4/book-page" -> buildString {
                                val page = checkNotNull(url.queryParameter("page")).toInt()
                                check(page in 0..2)
                                append("<div><h1>Section $page</h1>")
                                repeat(80) { index ->
                                    append("<p>Paragraph $index in section $page. ")
                                    append("A quiet river runs beside the garden. The reader follows its path ")
                                    append("through the valley and stops beside the old bridge.</p>")
                                }
                                append("</div>")
                            }
                            path == "/api/Reader/get-progress" -> latest.get()?.toString()
                                ?: """{"libraryId":1,"seriesId":2,"volumeId":3,"chapterId":4,"pageNum":0}"""
                            else -> {
                                code = 404
                                "{}"
                            }
                        }
                        val bytes = payload.toByteArray()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (!listener.isClosed && !(
                            error is SocketException &&
                                (
                                    error.message.orEmpty().contains("Broken pipe", true) ||
                                        error.message.orEmpty().contains("reset", true)
                                    )
                            )
                    ) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            listener.close()
            worker.join(5_000)
        }
    }
}
