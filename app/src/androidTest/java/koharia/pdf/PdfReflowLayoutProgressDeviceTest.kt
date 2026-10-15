package koharia.pdf

import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.epub.EpubPaginationPhase
import koharia.epub.EpubReaderActivity
import koharia.epub.EpubReaderFragment
import koharia.epub.EpubReaderViewModel
import koharia.epub.service.PdfReflowPublicationService
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapterRef
import koharia.pdf.cache.PdfReflowArtifact
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
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
class PdfReflowLayoutProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fontChangeAndNavigationPreserveOriginalPdfPageProgress() = exercise(conflict = false)

    @Test
    fun pendingOriginalPageConflictRequiresAnExplicitChoice() = exercise(conflict = true)

    private fun exercise(conflict: Boolean): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        var artifact: PdfReflowArtifact? = null
        val progress = Injekt.get<EpubProgressRepository>()
        val shared = Injekt.get<SharedAppPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.cacheDir, "pdf-layout-${UUID.randomUUID()}").canonicalFile
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
            val pdfBytes = reflowTextPdfFixture()
            val originalPdf = File(root, "original.pdf").apply { writeBytes(pdfBytes) }
            Server(originalPdf).use { server ->
                profiles.commitConfiguration(profile.id) { draft ->
                    KavitaPreferences(profile.id, draft).save(server.address, "fixture-key", server.account)
                }
                val source = Injekt.get<SourceManager>().get(profile.id) as KavitaSource
                val session = source.session()
                val ref = KavitaChapterRef(1, 2, 3, 4, 4)
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
                val prepared = PdfReflowPublicationService().prepare(source, manga, chapter, false)
                artifact = prepared
                awaitCondition("pdf-prepared") { prepared.manifest.isComplete }
                val manifest = prepared.manifest
                assertEquals(4, manifest.pageCount)
                assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                assertEquals(
                    java.security.MessageDigest.getInstance("SHA-256").digest(pdfBytes)
                        .joinToString("") { "%02x".format(it) },
                    manifest.sourceHash,
                )
                val derivedBytes = prepared.epub.readBytes()
                val seededOperation = if (conflict) {
                    val remote = koharia.kavita.KavitaProgress(1, 2, 3, 4, 0, null, "1970-01-01T00:00:01Z")
                    server.latest.set(JSONObject(session.api.json.encodeToString(remote)))
                    val pending = koharia.kavita.KavitaReadingState(
                        ref,
                        remote.copy(lastModifiedUtc = "1970-01-01T00:00:02Z"),
                        4,
                        baseline = remote,
                        conflict = true,
                    )
                    val operation = koharia.domain.kavita.KavitaOperation(
                        "progress/4",
                        session.api.json.encodeToString(pending),
                        1,
                        true,
                    )
                    Injekt.get<koharia.domain.kavita.KavitaRepository>()
                        .putOperation(source.id, session.accountKey, operation)
                    val locator = checkNotNull(koharia.pdf.reflow.PdfProgressMapper.initial(manifest, null, 0))
                    progress.upsertProgress(
                        koharia.domain.epub.model.EpubProgress(
                            chapter.id,
                            manga.id,
                            null,
                            locator.toJSON().toString(),
                            0.0,
                            null,
                            java.util.Date(2_000),
                            null,
                        ),
                    )
                    operation
                } else {
                    null
                }
                FixtureActivityLauncher.launch<EpubReaderActivity>(
                    EpubReaderActivity.newPdfReflowIntent(
                        context,
                        manga.id,
                        chapter.id,
                        source.id,
                        0,
                        koharia.epub.service.EpubReaderSupportResolution(
                            manga.id,
                            chapter.id,
                            source.id,
                            pdfReflowRevision = manifest.revision,
                        ),
                    ),
                ).use reader@{ scenario ->
                    lateinit var activity: EpubReaderActivity
                    lateinit var model: EpubReaderViewModel
                    scenario.onActivity {
                        activity = it
                        model = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                    }
                    awaitReady(model)
                    fun writeAllowed() = EpubReaderViewModel::class.java.getDeclaredField("remoteProgressWriteAllowed")
                        .apply { isAccessible = true }.getBoolean(model)
                    awaitCondition("initial-remote-decision") {
                        model.state.value.remoteProgressConflict != null || writeAllowed()
                    }
                    if (conflict) {
                        assertTrue(
                            "Pending PDF conflict must require a choice even at the same original page",
                            model.state.value.remoteProgressConflict != null,
                        )
                        assertTrue(!writeAllowed())
                        assertEquals(
                            seededOperation,
                            Injekt.get<koharia.domain.kavita.KavitaRepository>()
                                .operation(source.id, session.accountKey, "progress/4"),
                        )
                        assertEquals(null, checkNotNull(progress.getProgress(chapter.id)).lastSyncedAt)
                        assertTrue(server.writes.isEmpty())
                    }
                    if (model.state.value.remoteProgressConflict != null) {
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply { putString("pdf_initial_progress", "confirming-owned-server-page-zero") },
                        )
                        val label = context.stringResource(MR.strings.epub_reader_jump_remote_progress)
                        withTimeout(10_000) {
                            var button = findLabel(label)
                            while (button == null) {
                                delay(100)
                                button = findLabel(label)
                            }
                            var target = checkNotNull(button)
                            while (!target.isClickable) target = checkNotNull(target.parent)
                            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        }
                        awaitCondition("initial-remote-accepted") {
                            model.state.value.remoteProgressConflict == null &&
                                !model.state.value.isResolvingRemoteProgress && writeAllowed()
                        }
                    }
                    awaitReady(model)
                    if (conflict) {
                        assertTrue(!session.reading.hasPending(ref.chapterId))
                        assertTrue(
                            checkNotNull(session.reading.cached(ref.chapterId)).let {
                                !it.conflict && it.progress.pageNum == 0 && it.totalPages == 4
                            },
                        )
                        val accepted = checkNotNull(progress.getProgress(chapter.id))
                        assertEquals(java.util.Date(1_000), accepted.updatedAt)
                        assertEquals(accepted.updatedAt, accepted.lastSyncedAt)
                        assertTrue(server.writes.isEmpty())
                        assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                        assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "pdf_conflict_result",
                                    "pending-preserved;explicit-accept-confirmed;remote-writes=0",
                                )
                            },
                        )
                        awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        awaitCondition("reader-closed") { activity.isDestroyed }
                        return@reader
                    }
                    awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                    suspend fun navigateToOriginal(page: Int) {
                        repeat(30) {
                            val current = withContext(Dispatchers.Main) { model.currentOriginalPdfPage() }
                            if (current == page) return
                            check(current != null && current < page) {
                                "Skipped expected original page $page: $current"
                            }
                            val previous = model.state.value.currentVisualPage
                            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                                instrumentation.sendKeySync(
                                    KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON),
                                )
                            }
                            try {
                                awaitCondition("next-visual-toward-original-$page") {
                                    model.state.value.currentVisualPage != previous
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    val fragment = activity.supportFragmentManager.fragments
                                        .filterIsInstance<EpubReaderFragment>().single()
                                    fun field(name: String) = EpubReaderFragment::class.java.getDeclaredField(name)
                                        .apply { isAccessible = true }.get(fragment)
                                    instrumentation.sendStatus(
                                        2,
                                        Bundle().apply {
                                            putString(
                                                "pdf_turn_state",
                                                "focus=${activity.hasWindowFocus()};" +
                                                    "conflict=${model.state.value.remoteProgressConflict != null};" +
                                                    "before=$previous;after=${model.state.value.currentVisualPage};" +
                                                    "original=${model.currentOriginalPdfPage()};" +
                                                    "frame=${field(
                                                        "currentTransitionPageIndex",
                                                    )}/${field("currentTransitionPageCount")};" +
                                                    "frameHref=${field("currentTransitionHref")};" +
                                                    "locator=${model.currentLocator()?.toJSON()}",
                                            )
                                        },
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>()
                                    .single().capturePdfSourceAnchor()
                            }
                        }
                        assertEquals(page, model.currentOriginalPdfPage())
                    }
                    suspend fun saveAndConfirm(page: Int) {
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        withTimeout(45_000) {
                            while (session.reading.hasPending(ref.chapterId)) delay(100)
                        }
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        val stored = checkNotNull(progress.getProgress(chapter.id))
                        val mapped = JSONObject(stored.locatorJson).getJSONObject("kohariaPdfSource")
                        assertEquals(page, mapped.getInt("page"))
                        assertEquals(manifest.sourceHash, mapped.getString("sourceHash"))
                        assertEquals(manifest.revision, mapped.getString("revision"))
                        assertEquals(page.toLong(), checkNotNull(chapters.getChapterById(chapter.id)).lastPageRead)
                        val operation = checkNotNull(session.reading.cached(ref.chapterId))
                        fun field(name: String) = EpubReaderViewModel::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(model)
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "pdf_save_state",
                                    "expected=$page;operation=${operation.progress.pageNum};" +
                                        "remote=${server.latest.get()?.optInt("pageNum")};" +
                                        "pending=${session.reading.hasPending(
                                            ref.chapterId,
                                        )};conflict=${operation.conflict};" +
                                        "saved=${stored.updatedAt.time};ack=${stored.lastSyncedAt?.time};" +
                                        "operationTime=${operation.progress.lastModifiedUtc};" +
                                        "revision=${field("localReadingRevision")}/${field("recordedReadingRevision")}",
                                )
                            },
                        )
                        assertEquals(stored.updatedAt, stored.lastSyncedAt)
                        assertEquals(ref, operation.ref)
                        assertEquals(4, operation.totalPages)
                        assertEquals(page, operation.progress.pageNum)
                        assertTrue(operation.progress.bookScrollId.isNullOrEmpty())
                        assertTrue(!operation.conflict && !session.reading.hasPending(ref.chapterId))
                        assertEquals(page, checkNotNull(server.latest.get()).getInt("pageNum"))
                    }
                    navigateToOriginal(1)
                    saveAndConfirm(1)
                    awaitReady(model)
                    val before = model.state.value
                    val beforeTotal = checkNotNull(before.totalVisualPages)
                    val beforeWrites = server.writes.size
                    status("original-second-page", before.currentVisualPage, beforeTotal, server)
                    suspend fun changeFontAndAwaitViewport(size: Float) {
                        val generation = model.state.value.paginationGeneration
                        withContext(Dispatchers.Main) {
                            EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                                .fontSize.set(size)
                        }
                        awaitCondition("new-layout-generation") {
                            model.state.value.paginationGeneration > generation
                        }
                        awaitReady(model)
                        withTimeout(45_000) {
                            while (true) {
                                val rendered = withContext(Dispatchers.Main) {
                                    val fragment = activity.supportFragmentManager.fragments
                                        .filterIsInstance<EpubReaderFragment>().single()
                                    fragment.capturePdfSourceAnchor()
                                    val frame = EpubReaderFragment::class.java.getDeclaredField(
                                        "currentTransitionPageIndex",
                                    )
                                        .apply { isAccessible = true }.getInt(fragment)
                                    model.currentOriginalPdfPage() == 1 &&
                                        frame + 1 == model.state.value.currentVisualPage
                                }
                                if (rendered) break
                                delay(100)
                            }
                        }
                    }
                    changeFontAndAwaitViewport(2.0f)
                    assertEquals(1, model.currentOriginalPdfPage())
                    saveAndConfirm(1)
                    val afterTotal = checkNotNull(model.state.value.totalVisualPages)
                    assertTrue("Font size must increase actual visual pages", afterTotal > beforeTotal)
                    assertTrue(afterTotal > manifest.pageCount)
                    for (write in server.writes.drop(beforeWrites)) assertEquals(1, write.getInt("pageNum"))
                    status("layout-kept-original-page", model.state.value.currentVisualPage, afterTotal, server)
                    changeFontAndAwaitViewport(1.0f)
                    assertEquals(beforeTotal, model.state.value.totalVisualPages)
                    saveAndConfirm(1)
                    status("cached-small-layout-kept-page", model.state.value.currentVisualPage, beforeTotal, server)
                    changeFontAndAwaitViewport(2.0f)
                    assertEquals(afterTotal, model.state.value.totalVisualPages)
                    saveAndConfirm(1)
                    status("cached-large-layout-kept-page", model.state.value.currentVisualPage, afterTotal, server)
                    navigateToOriginal(2)
                    saveAndConfirm(2)
                    status("third-original-page-confirmed", model.state.value.currentVisualPage, afterTotal, server)
                    assertEquals(chapter.id, model.state.value.chapterId)
                    assertEquals(profile.id, model.state.value.sourceId)
                    assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                    assertEquals(
                        java.security.MessageDigest.getInstance("SHA-256").digest(pdfBytes)
                            .joinToString("") { "%02x".format(it) },
                        manifest.sourceHash,
                    )
                    assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                    for (write in server.writes) {
                        assertEquals(1L, write.getLong("libraryId"))
                        assertEquals(2L, write.getLong("seriesId"))
                        assertEquals(3L, write.getLong("volumeId"))
                        assertEquals(4L, write.getLong("chapterId"))
                        assertTrue(write.getInt("pageNum") in 0..2)
                        assertTrue(write.optString("bookScrollId").let { it.isEmpty() || it == "null" })
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
                artifact?.progressive?.stopAndJoin()
                val ownedCache = File(context.cacheDir, "pdf-reflow/${profile.id}").canonicalFile
                check(ownedCache.parentFile == File(context.cacheDir, "pdf-reflow").canonicalFile)
                check(!ownedCache.exists() || ownedCache.deleteRecursively())
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

    private fun findLabel(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
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

    private fun status(phase: String, page: Int?, total: Int?, server: Server) {
        val value = checkNotNull(server.latest.get())
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "layout_progress",
                    "$phase;visual=$page/$total;resource=${value.getInt("pageNum")};" +
                        "originalTotal=4;writes=${server.writes.size}",
                )
            },
        )
    }

    private class Server(private val pdf: File) : AutoCloseable {
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
                                """{"id":4,"volumeId":3,"title":"Layout","pages":4,"format":4,
                                    "lastModifiedUtc":"2026-01-01T00:00:00Z"}"""
                            path == "/api/Download/chapter" -> ""
                            path == "/api/Reader/get-progress" -> latest.get()?.toString()
                                ?: """{"libraryId":1,"seriesId":2,"volumeId":3,"chapterId":4,"pageNum":0}"""
                            else -> {
                                code = 404
                                "{}"
                            }
                        }
                        val bytes = if (path == "/api/Download/chapter" &&
                            method == "GET"
                        ) {
                            pdf.readBytes()
                        } else {
                            payload.toByteArray()
                        }
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $code Fixture\r\nContent-Type: " +
                                        (
                                            if (path ==
                                                "/api/Download/chapter"
                                            ) {
                                                "application/pdf"
                                            } else {
                                                "application/json"
                                            }
                                            ) +
                                        "\r\n" +
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
