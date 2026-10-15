package koharia.pdf

import android.os.Bundle
import android.view.KeyEvent
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
import koharia.pdf.cache.PdfReflowArtifact
import koharia.smanga.SmangaChapter
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
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
import tachiyomi.core.common.i18n.stringResource
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
class SmangaPdfReflowProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun emptyRemoteThenActualOriginalPageIsDurableAndConfirmed() = exercise(conflict = false)

    @Test
    fun pendingOriginalPageRequiresActualRemoteChoice() = exercise(conflict = true)

    @Test
    fun offlineCachedReflowPreservesOriginalPageAndPendingAcrossReopen() = exercise(conflict = false, offline = true)

    private fun exercise(conflict: Boolean, offline: Boolean = false): Unit = runBlocking(Dispatchers.IO) {
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
        val root = File(context.cacheDir, "smanga-pdf-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val profile = profiles.add("smanga", "PDF progress ${UUID.randomUUID()}")
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
                SmangaPreferences(profile.id).save(server.address, "fixture", "fixture-password", 147)
                awaitCondition("source-registered") { Injekt.get<SourceManager>().get(profile.id) is SmangaSource }
                val source = (Injekt.get<SourceManager>().get(profile.id) as SmangaSource).also { it.reload() }
                val session = source.session()
                val ref = SmangaChapter(3, 2, 1, "PDF fixture", format = "pdf")
                val repository = Injekt.get<koharia.domain.smanga.SmangaRepository>()
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = session.mangaUrl(2),
                            title = "Layout book",
                        ),
                    ),
                ).single()
                val chapter = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = session.chapterUrl(ref),
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
                val pending = if (conflict) {
                    server.latest.set(
                        JSONObject().put("mangaId", 2).put("chapterId", 3).put("page", 1)
                            .put("count", 4).put("finish", 0).put("updateTime", 1_000_000_000_000L),
                    )
                    val value = koharia.domain.smanga.SmangaReadState(
                        3,
                        2,
                        1,
                        4,
                        false,
                        999_999_999_000L,
                        1,
                        pending = true,
                    )
                    repository.putReadState(source.id, session.accountKey, value)
                    val locator = checkNotNull(koharia.pdf.reflow.PdfProgressMapper.initial(manifest, null, 1))
                    progress.upsertProgress(
                        koharia.domain.epub.model.EpubProgress(
                            chapter.id,
                            manga.id,
                            null,
                            locator.toJSON().toString(),
                            0.0,
                            null,
                            java.util.Date(value.readAt),
                            null,
                        ),
                    )
                    value
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
                        assertTrue(model.state.value.remoteProgressConflict != null)
                        assertTrue(!writeAllowed())
                        assertEquals(pending, repository.readState(source.id, session.accountKey, ref.id))
                        assertEquals(null, checkNotNull(progress.getProgress(chapter.id)).lastSyncedAt)
                        assertTrue(server.writes.isEmpty())
                        assertTrue(server.history.isEmpty())
                        clickLabel(context.stringResource(tachiyomi.i18n.MR.strings.epub_reader_jump_remote_progress))
                        awaitCondition("actual-remote-choice-accepted") {
                            model.state.value.remoteProgressConflict == null &&
                                !model.state.value.isResolvingRemoteProgress && writeAllowed()
                        }
                        awaitReady(model)
                        val accepted = checkNotNull(repository.readState(source.id, session.accountKey, ref.id))
                        assertEquals(0, accepted.pageIndex)
                        assertEquals(4, accepted.totalPages)
                        assertEquals(1_000_000_000_000L, accepted.readAt)
                        assertTrue(!accepted.pending)
                        assertTrue(accepted.revision > checkNotNull(pending).revision)
                        val local = checkNotNull(progress.getProgress(chapter.id))
                        assertEquals(java.util.Date(accepted.readAt), local.updatedAt)
                        assertEquals(local.updatedAt, local.lastSyncedAt)
                        assertEquals(0, model.currentOriginalPdfPage())
                        assertEquals(profile.id, model.state.value.sourceId)
                        assertEquals(chapter.id, model.state.value.chapterId)
                        assertTrue(server.writes.isEmpty())
                        assertTrue(server.history.isEmpty())
                        assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                        assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString("pdf_choice", "pending-preserved;actual-remote-click;confirmed;writes=0")
                            },
                        )
                        awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        awaitCondition("reader-closed") { activity.isDestroyed }
                        return@reader
                    }
                    assertEquals(null, model.state.value.remoteProgressConflict)
                    assertTrue(writeAllowed())
                    assertTrue(server.writes.isEmpty())
                    assertTrue(server.history.isEmpty())
                    assertEquals(null, repository.readState(source.id, session.accountKey, ref.id))
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
                            while (repository.readState(source.id, session.accountKey, ref.id)?.let {
                                    it.pageIndex == page && !it.pending
                                } != true
                            ) {
                                delay(100)
                            }
                        }
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        val stored = checkNotNull(progress.getProgress(chapter.id))
                        val mapped = JSONObject(stored.locatorJson).getJSONObject("kohariaPdfSource")
                        assertEquals(page, mapped.getInt("page"))
                        assertEquals(manifest.sourceHash, mapped.getString("sourceHash"))
                        assertEquals(manifest.revision, mapped.getString("revision"))
                        assertEquals(page.toLong(), checkNotNull(chapters.getChapterById(chapter.id)).lastPageRead)
                        val state = checkNotNull(repository.readState(source.id, session.accountKey, ref.id))
                        assertEquals(stored.updatedAt, stored.lastSyncedAt)
                        assertEquals(stored.updatedAt.time, state.readAt)
                        assertEquals(4, state.totalPages)
                        assertEquals(page, state.pageIndex)
                        assertTrue(!state.pending)
                        assertTrue(session.reading.isPageConfirmed(ref.id, page, 4, stored.updatedAt.time))
                        assertEquals(page + 1, checkNotNull(server.latest.get()).getInt("page"))
                        assertEquals(4, checkNotNull(server.latest.get()).getInt("count"))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "smanga_pdf_saved",
                                    "original=$page/4;visual=${model.state.value.currentVisualPage}/" +
                                        "${model.state.value.totalVisualPages};confirmed=true;pending=false",
                                )
                            },
                        )
                    }
                    navigateToOriginal(1)
                    saveAndConfirm(1)
                    assertTrue(checkNotNull(model.state.value.totalVisualPages) > manifest.pageCount)
                    assertEquals(chapter.id, model.state.value.chapterId)
                    assertEquals(profile.id, model.state.value.sourceId)
                    assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                    assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                    assertTrue(server.history.isNotEmpty())
                    for (write in server.writes) {
                        assertEquals(2L, write.getLong("mangaId"))
                        assertEquals(3L, write.getLong("chapterId"))
                        assertEquals(4, write.getInt("count"))
                        assertTrue(write.getInt("page") in 1..2)
                    }
                    if (offline) {
                        val before = checkNotNull(progress.getProgress(chapter.id))
                        val beforeState = checkNotNull(repository.readState(source.id, session.accountKey, ref.id))
                        val remoteBefore = checkNotNull(server.latest.get()).toString()
                        val writesBefore = server.writes.size
                        val historyBefore = server.history.size
                        val totalBefore = checkNotNull(model.state.value.totalVisualPages)
                        server.available.set(false)
                        val generation = model.state.value.paginationGeneration
                        withContext(Dispatchers.Main) {
                            EpubLayoutPreferences(model.readerSettingsStore(shared.store(), persistChanges = true))
                                .fontSize.set(2.0f)
                        }
                        awaitCondition("offline-layout-generation") {
                            model.state.value.paginationGeneration > generation
                        }
                        awaitReady(model)
                        awaitCondition("offline-rendered-original-page") {
                            val fragment = activity.supportFragmentManager.fragments
                                .filterIsInstance<EpubReaderFragment>().single()
                            fragment.capturePdfSourceAnchor()
                            val frame = EpubReaderFragment::class.java.getDeclaredField("currentTransitionPageIndex")
                                .apply { isAccessible = true }.getInt(fragment)
                            model.currentOriginalPdfPage() == 1 && frame + 1 == model.state.value.currentVisualPage
                        }
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        assertTrue(checkNotNull(model.state.value.totalVisualPages) > totalBefore)
                        assertEquals(before.updatedAt, checkNotNull(progress.getProgress(chapter.id)).updatedAt)
                        assertEquals(beforeState, repository.readState(source.id, session.accountKey, ref.id))
                        assertEquals(remoteBefore, checkNotNull(server.latest.get()).toString())
                        assertEquals(writesBefore, server.writes.size)
                        assertEquals(historyBefore, server.history.size)
                        navigateToOriginal(2)
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        withTimeout(45_000) {
                            while (repository.readState(source.id, session.accountKey, ref.id)?.let {
                                    it.pageIndex == 2 && it.totalPages == 4 && it.pending
                                } != true
                            ) {
                                delay(100)
                            }
                        }
                        val saved = checkNotNull(progress.getProgress(chapter.id))
                        assertEquals(2, JSONObject(saved.locatorJson).getJSONObject("kohariaPdfSource").getInt("page"))
                        assertEquals(2L, checkNotNull(chapters.getChapterById(chapter.id)).lastPageRead)
                        assertTrue(saved.updatedAt != saved.lastSyncedAt)
                        assertTrue(saved.updatedAt > before.updatedAt)
                        assertEquals(remoteBefore, checkNotNull(server.latest.get()).toString())
                        assertEquals(writesBefore, server.writes.size)
                        assertEquals(historyBefore, server.history.size)
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "pdf_offline",
                                    "layout-kept-original-1;next-original-2-pending;remote-unchanged",
                                )
                            },
                        )
                    }
                    awaitCondition("reader-window-focused") { activity.hasWindowFocus() }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitCondition("reader-closed") { activity.isDestroyed }
                }
                awaitCondition("session-released") { Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null }
                if (offline) {
                    val pendingBefore = checkNotNull(repository.readState(source.id, session.accountKey, ref.id))
                    val remoteBefore = checkNotNull(server.latest.get()).toString()
                    val writesBefore = server.writes.size
                    FixtureActivityLauncher.launch<EpubReaderActivity>(
                        EpubReaderActivity.newPdfReflowIntent(
                            context,
                            manga.id,
                            chapter.id,
                            source.id,
                            resolution = koharia.epub.service.EpubReaderSupportResolution(
                                manga.id,
                                chapter.id,
                                source.id,
                                pdfReflowRevision = manifest.revision,
                            ),
                        ),
                    ).use { scenario ->
                        lateinit var activity: EpubReaderActivity
                        lateinit var model: EpubReaderViewModel
                        scenario.onActivity {
                            activity = it
                            model = ViewModelProvider(it)[EpubReaderViewModel::class.java]
                        }
                        awaitReady(model)
                        awaitCondition("offline-reopened-original-page-2") {
                            val fragment = activity.supportFragmentManager.fragments
                                .filterIsInstance<EpubReaderFragment>().single()
                            fragment.capturePdfSourceAnchor()
                            model.currentOriginalPdfPage() == 2 && activity.hasWindowFocus()
                        }
                        assertEquals(profile.id, model.state.value.sourceId)
                        assertEquals(chapter.id, model.state.value.chapterId)
                        assertEquals(pendingBefore, repository.readState(source.id, session.accountKey, ref.id))
                        assertEquals(remoteBefore, checkNotNull(server.latest.get()).toString())
                        assertEquals(writesBefore, server.writes.size)
                        assertTrue(server.unavailableRequests.get() > 0)
                        assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                        assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString("pdf_offline", "reopened-cached-original-2;owner-pending-bytes-preserved")
                            },
                        )
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        awaitCondition("offline-reopened-reader-closed") { activity.isDestroyed }
                    }
                    awaitCondition("offline-session-released") {
                        Injekt.get<EpubReaderSessionRepository>().get(chapter.id) == null
                    }
                }
                val captured = source.captureReflowProgressSession(chapter)
                val originalState = repository.readState(source.id, session.accountKey, ref.id)
                source.reload()
                val failure = runCatching { captured.adapter.recordLocalPageProgress(chapter.url, 2, 4, 100) }
                assertTrue("Old captured session must reject writes", failure.isFailure)
                assertEquals(originalState, repository.readState(source.id, session.accountKey, ref.id))
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
                connections.flush()
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("layout_cleanup", "owned-profile-cache-root-removed;preferences-restored") },
            )
        }
    }

    private suspend fun clickLabel(label: String) = withTimeout(10_000) {
        fun find(
            node: android.view.accessibility.AccessibilityNodeInfo?,
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) break
            delay(100)
        }
    }

    private suspend fun awaitReady(model: EpubReaderViewModel) = awaitCondition("pagination-ready") {
        val state = model.state.value
        check(state.errorMessage == null) { state.errorMessage.orEmpty() }
        state.isReady && model.currentLocator() != null &&
            state.paginationPhase in setOf(EpubPaginationPhase.READY, EpubPaginationPhase.CACHED) &&
            (state.totalVisualPages ?: 0) > 0
    }

    private suspend fun awaitCondition(phase: String, condition: suspend () -> Boolean) {
        instrumentation.sendStatus(2, Bundle().apply { putString("layout_wait", phase) })
        withTimeout(45_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(100)
        }
    }

    private class Server(private val pdf: File) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${listener.localPort}/"
        val available = java.util.concurrent.atomic.AtomicBoolean(true)
        val unavailableRequests = java.util.concurrent.atomic.AtomicInteger()
        val writes = CopyOnWriteArrayList<JSONObject>()
        val latest = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()
        val unexpectedMutations = CopyOnWriteArrayList<String>()
        val history = CopyOnWriteArrayList<JSONObject>()
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
                        if (!available.get()) {
                            unavailableRequests.incrementAndGet()
                            socket.getOutputStream().write(
                                "HTTP/1.1 503 Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                            return@use
                        }
                        var code = 200
                        val payload = when {
                            method == "POST" && path == "/api/login" ->
                                """{"code":200,"data":{"token":"fixture-token","userId":147,"userName":"fixture"}}"""
                            method == "POST" && path == "/api/latest" -> {
                                val value = JSONObject(body.toString(Charsets.UTF_8))
                                assertEquals(3L, value.getLong("chapterId"))
                                writes += value
                                val acknowledged = JSONObject(
                                    value.toString(),
                                ).put("updateTime", System.currentTimeMillis())
                                latest.set(acknowledged)
                                JSONObject().put("code", 200).put("data", acknowledged).toString()
                            }
                            method == "POST" && path == "/api/history" -> {
                                history += JSONObject(body.toString(Charsets.UTF_8))
                                """{"code":200,"data":{}}"""
                            }
                            method != "GET" -> {
                                unexpectedMutations += "$method $path"
                                code = 405
                                "{}"
                            }
                            path == "/api/chapter" ->
                                """{"code":200,"list":[{"chapterId":3,"mangaId":2,"mediaId":1,
                                    "chapterName":"PDF fixture","chapterType":"pdf","pageCount":0,
                                    "updateTime":1000,"latest":${latest.get() ?: "null"}}],"count":1}"""
                            path == "/api/opds/chapter/3/download" -> ""
                            else -> {
                                code = 404
                                "{}"
                            }
                        }
                        val bytes = if (path == "/api/opds/chapter/3/download" &&
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
                                                "/api/opds/chapter/3/download"
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
