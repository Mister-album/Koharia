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
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.BookMetadataDto
import koharia.komga.api.dto.MediaDto
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Credentials
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class KomgaEpubLayoutProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fontChangeKeepsCloudLocatorIndependentOfVisualPages(): Unit = runBlocking(Dispatchers.IO) {
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
        val root = File(context.cacheDir, "komga-layout-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val profile = profiles.add("komga", "Layout progress ${UUID.randomUUID()}")
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
            val seed = File(root, "layout.epub")
            writeEpub(seed)
            val bytes = seed.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val book = BookDto(
                id = "layout",
                seriesId = "series",
                libraryId = "library",
                name = "layout.epub",
                fileLastModified = "2026-01-01T00:00:00Z",
                fileHash = hash,
                sizeBytes = bytes.size.toLong(),
                media = MediaDto(status = "READY", mediaType = "application/epub+zip", mediaProfile = "EPUB"),
                metadata = BookMetadataDto(title = "Layout book"),
            )
            Server(bytes, Json.encodeToString(BookDto.serializer(), book)).use { server ->
                profiles.commitConfiguration(profile.id) { draft ->
                    check(
                        draft.edit().putString("Address", server.address).putString("AuthMode", "Credentials")
                            .putString("Username", "fixture").putString("Password", "layout-fixture").commit(),
                    )
                }
                val source = Injekt.get<SourceManager>().get(profile.id) as KomgaSource
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = "${server.address}/api/v1/series/series",
                            title = "Layout book",
                        ),
                    ),
                ).single()
                val chapter = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "${server.address}/api/v1/books/layout",
                            name = "Layout book",
                            memo = KomgaChapterMemo.buildMemo(server.address, book),
                        ),
                    ),
                ).single()
                val key = "komga:$hash"
                val cached = checkNotNull(cache.cacheCompleteBook(source, chapter.url, key))
                assertTrue(bytes.contentEquals(cached.readBytes()))
                val resolution = EpubReaderSupportResolver().resolve(manga.id, chapter.id)
                assertTrue(resolution.isCompleteCache && resolution.isNativeSupported)
                assertEquals(chapter.url, resolution.remoteBookUrl)
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
                    awaitCondition("authoritative-positions") {
                        Injekt.get<EpubReaderSessionRepository>().get(chapter.id)
                            ?.positionsController?.hasAuthoritativePositions == true
                    }
                    val positions = withContext(Dispatchers.Main) {
                        checkNotNull(Injekt.get<EpubReaderSessionRepository>().get(chapter.id))
                            .positionsController.currentPositions()
                    }
                    assertTrue(
                        "The fixture needs multiple authoritative positions per resource: ${positions.size}",
                        positions.size > 3,
                    )
                    val target = positions.first { it.href.toString() == "s2.xhtml" }
                    withContext(Dispatchers.Main) {
                        assertTrue(
                            activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                                .single().goTo(target),
                        )
                    }
                    awaitCondition("section-two") { model.currentLocator()?.href?.toString() == "s2.xhtml" }
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    awaitCondition("initial-cloud-locator") {
                        server.writes.any { it.getJSONObject("locator").getString("href") == "s2.xhtml" }
                    }
                    awaitReady(model)
                    val before = withContext(Dispatchers.Main) { model.state.value }
                    val originalLocator = checkNotNull(server.latest.get()).getJSONObject("locator")
                    assertAuthoritative(originalLocator, positions)
                    val beforeWrites = server.writes.size
                    val beforeGeneration = before.paginationGeneration
                    val beforeTotal = checkNotNull(before.totalVisualPages)
                    status("before", before.currentVisualPage, beforeTotal, positions.size, beforeWrites)

                    withContext(Dispatchers.Main) {
                        EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                            .fontSize.set(2.0f)
                    }
                    awaitCondition("new-layout-generation") {
                        model.state.value.paginationGeneration > beforeGeneration
                    }
                    awaitReady(model)
                    val after = withContext(Dispatchers.Main) { model.state.value }
                    val afterTotal = checkNotNull(after.totalVisualPages)
                    assertTrue("Font size must change the measured visual page total", afterTotal > beforeTotal)
                    assertTrue(
                        "Fixture must distinguish visual pages from publication positions",
                        afterTotal != positions.size,
                    )
                    withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                    val currentPositions = withContext(Dispatchers.Main) {
                        checkNotNull(Injekt.get<EpubReaderSessionRepository>().get(chapter.id))
                            .positionsController.currentPositions()
                    }
                    assertEquals(positions, currentPositions)
                    for (write in server.writes.drop(beforeWrites)) {
                        assertLocationEquals(originalLocator, write.getJSONObject("locator"))
                    }
                    assertLocationEquals(originalLocator, checkNotNull(server.latest.get()).getJSONObject("locator"))
                    status(
                        "layout-kept-cloud-location",
                        after.currentVisualPage,
                        afterTotal,
                        positions.size,
                        server.writes.size,
                    )

                    val nextProgression = checkNotNull(
                        positions.first {
                            it.href.toString() == "s2.xhtml" &&
                                checkNotNull(it.locations.progression) >
                                originalLocator.getJSONObject("locations").getDouble("progression")
                        }.locations.progression,
                    )
                    for (page in 1..10) {
                        val previousProgression = model.currentLocator()?.locations?.progression
                        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                            instrumentation.sendKeySync(
                                KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                            )
                        }
                        awaitCondition("forward-after-layout-$page") {
                            model.currentLocator()?.href?.toString() == "s2.xhtml" &&
                                model.currentLocator()?.locations?.progression != previousProgression
                        }
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        val uploaded = checkNotNull(server.latest.get()).getJSONObject("locator")
                        assertAuthoritative(uploaded, positions)
                        val localProgression = checkNotNull(model.currentLocator()?.locations?.progression)
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "layout_step",
                                    "page=$page;local=$localProgression;next=$nextProgression;" +
                                        "cloud=${uploaded.getJSONObject("locations").getDouble("progression")}",
                                )
                            },
                        )
                        if (localProgression >= nextProgression) break
                    }
                    val finalLocator = checkNotNull(server.latest.get()).getJSONObject("locator")
                    assertAuthoritative(finalLocator, positions)
                    assertTrue(
                        finalLocator.getJSONObject("locations").getDouble("progression") >
                            originalLocator.getJSONObject("locations").getDouble("progression"),
                    )
                    assertEquals(chapter.id, model.state.value.chapterId)
                    assertEquals(profile.id, model.state.value.sourceId)
                    val stored = checkNotNull(progress.getProgress(chapter.id))
                    assertEquals(stored.updatedAt, stored.lastSyncedAt)
                    assertTrue(bytes.contentEquals(cached.readBytes()))
                    val finalState = model.state.value
                    status(
                        "forward-uploaded-authoritative-locator",
                        finalState.currentVisualPage,
                        finalState.totalVisualPages,
                        positions.size,
                        server.writes.size,
                    )
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitCondition("reader-closed") { activity.isDestroyed }
                }
                awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
                assertTrue(server.unexpectedMutations.isEmpty())
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

    private fun assertAuthoritative(locator: JSONObject, positions: List<Locator>) {
        val locations = locator.getJSONObject("locations")
        val position = positions.single { it.locations.position == locations.getInt("position") }
        assertEquals(position.href.toString(), locator.getString("href"))
        assertEquals(position.locations.progression!!, locations.getDouble("progression"), 0.000001)
        assertEquals(position.locations.totalProgression!!, locations.getDouble("totalProgression"), 0.000001)
        assertTrue(!locations.has("totalPages") && !locations.has("currentPage"))
    }

    private fun assertLocationEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.getString("href"), actual.getString("href"))
        val first = expected.getJSONObject("locations")
        val second = actual.getJSONObject("locations")
        for (name in listOf("position", "progression", "totalProgression")) {
            assertEquals(first.getDouble(name), second.getDouble(name), 0.000001)
        }
    }

    private fun status(phase: String, page: Int?, total: Int?, positions: Int, writes: Int) {
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("layout_progress", "$phase;visual=$page/$total;positions=$positions;writes=$writes")
            },
        )
    }

    private class Server(private val epub: ByteArray, private val book: String) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${listener.localPort}"
        val writes = CopyOnWriteArrayList<JSONObject>()
        val latest = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val unexpectedMutations = CopyOnWriteArrayList<String>()
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
                        val path = request[1].substringBefore('?')
                        val headers = buildMap {
                            while (true) {
                                val header = line()
                                if (header.isBlank()) break
                                put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                            }
                        }
                        val length = headers["content-length"]?.toInt() ?: 0
                        check(length in 0..100_000)
                        val payload = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(payload, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        val valid = headers["authorization"] == Credentials.basic("fixture", "layout-fixture")
                        var status = if (valid) 200 else 401
                        val body = if (!valid) {
                            "{}".toByteArray()
                        } else if (method == "PUT" && path == "/api/v1/books/layout/progression") {
                            val parsed = JSONObject(payload.decodeToString())
                            val retained = JSONObject().put("locator", parsed.getJSONObject("locator"))
                                .put("modified", parsed.getString("modified"))
                            writes += retained
                            latest.set(retained)
                            ByteArray(0)
                        } else if (method == "GET") {
                            when (path) {
                                "/api/v1/books/layout/file" -> epub
                                "/api/v1/books/layout" -> book.toByteArray()
                                "/api/v1/books/layout/progression" -> (latest.get()?.toString() ?: "\"\"").toByteArray()
                                "/api/v1/users/me" ->
                                    """{"id":"fixture","email":"fixture@example.test","roles":["ADMIN","FILE_DOWNLOAD"]}"""
                                        .toByteArray()
                                "/api/v1/libraries" -> "[]".toByteArray()
                                else -> {
                                    status = 404
                                    "{}".toByteArray()
                                }
                            }
                        } else {
                            unexpectedMutations += "$method $path"
                            status = 405
                            "{}".toByteArray()
                        }
                        val media = if (path.endsWith("/file")) "application/epub+zip" else "application/json"
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\nContent-Type: $media\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(body)
                            flush()
                        }
                    }
                } catch (error: Exception) {
                    val disconnected = error is SocketException && error.message?.let {
                        it.contains("Broken pipe", true) || it.contains("Connection reset", true)
                    } == true
                    if (!listener.isClosed && !disconnected) failure.compareAndSet(null, error)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        override fun close() {
            listener.close()
            worker.join(5_000)
            check(!worker.isAlive)
        }
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """.trimIndent(),
            )
            val manifest = (1..3).joinToString("") {
                """<item id="s$it" href="s$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (1..3).joinToString("") { """<itemref idref="s$it"/>""" }
            entry(
                "book.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">layout-fixture</dc:identifier>
                <dc:title>Layout book</dc:title><dc:language>en</dc:language></metadata>
                <manifest>$manifest</manifest><spine>$spine</spine></package>
                """.trimIndent(),
            )
            for (section in 1..3) {
                val text = (1..40).joinToString("") { paragraph ->
                    "<p id='p$paragraph'>Section $section paragraph $paragraph. " +
                        (
                            "The reader keeps a stable publication location " +
                                "while typography changes the visual page count. "
                            ).repeat(5) + "</p>"
                }
                entry(
                    "s$section.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Section $section</title></head>
                    <body>$text</body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
