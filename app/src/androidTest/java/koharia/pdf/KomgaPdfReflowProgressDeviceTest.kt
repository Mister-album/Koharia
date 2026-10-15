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
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class KomgaPdfReflowProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun emptyRemoteThenActualOriginalPageIsDurableAndConfirmed() = exercise(conflict = false)

    @Test
    fun heldOriginalPageRequiresActualRemoteChoice() = exercise(conflict = true)

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
        val root = File(context.cacheDir, "komga-pdf-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val profile = profiles.add("komga", "PDF progress ${UUID.randomUUID()}")
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
                suspend fun configure() = profiles.commitConfiguration(profile.id) { draft ->
                    check(
                        draft.edit().putString("Address", server.address).putString("AuthMode", "Credentials")
                            .putString("Username", "fixture").putString("Password", "pdf-fixture").commit(),
                    )
                }
                configure()
                val source = Injekt.get<SourceManager>().get(profile.id) as KomgaSource
                val account = koharia.source.komga.captureKomgaProgressSession(source, Injekt.get()).account.key
                val url = "${server.address}/api/v1/books/book"
                val key = koharia.komga.progress.KomgaProgressOrdering.key(source.id, account, url)
                val repository = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
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
                            url = url,
                            name = "Layout book",
                            memo = kotlinx.serialization.json.buildJsonObject {
                                put("fileHash", kotlinx.serialization.json.JsonPrimitive(server.hash))
                                put("pagesCount", kotlinx.serialization.json.JsonPrimitive(4))
                                put("sizeBytes", kotlinx.serialization.json.JsonPrimitive(pdfBytes.size))
                                put("mediaProfile", kotlinx.serialization.json.JsonPrimitive("PDF"))
                            },
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
                server.latest.set(JSONObject("""{"completed":true,"lastModified":"2026-01-01T00:00:00Z"}"""))
                val comparison = koharia.epub.service.PdfReflowProgressAdapter(
                    source.captureReflowProgressSession(chapter).adapter,
                    chapter,
                    manifest,
                ).pullEpubProgress(chapter.url)
                assertEquals(
                    3,
                    koharia.pdf.reflow.PdfProgressMapper.block(
                        manifest,
                        checkNotNull(comparison).locator,
                        null,
                    )?.source?.page,
                )
                assertNull(repository.get(key))
                assertTrue(server.writes.isEmpty())
                server.latest.set(null)
                val pending = if (conflict) {
                    server.latest.set(
                        JSONObject().put("page", 1).put("completed", false)
                            .put("readDate", "2001-09-09T01:46:40Z")
                            .put("lastModified", "2001-09-09T01:46:40Z"),
                    )
                    val recorded = checkNotNull(repository.record(key, 0, 1, 4, 999_999_999_000L, null) {})
                    assertTrue(repository.transition(recorded, koharia.domain.komga.KomgaReadStatusPhase.HELD) {})
                    val value = checkNotNull(repository.get(key))
                    assertEquals(koharia.domain.komga.KomgaReadStatusPhase.HELD, value.phase)
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
                        assertEquals(pending, repository.get(key))
                        assertNull(repository.attempt(key))
                        assertNull(checkNotNull(progress.getProgress(chapter.id)).lastSyncedAt)
                        assertTrue(server.writes.isEmpty())
                        clickLabel(context.stringResource(tachiyomi.i18n.MR.strings.epub_reader_jump_remote_progress))
                        awaitCondition("actual-remote-choice-accepted") {
                            model.state.value.remoteProgressConflict == null &&
                                !model.state.value.isResolvingRemoteProgress && writeAllowed()
                        }
                        awaitReady(model)
                        val accepted = checkNotNull(repository.get(key))
                        assertEquals(0L, accepted.page)
                        assertEquals(4L, accepted.total)
                        assertEquals(1_000_000_000_000L, accepted.readAt)
                        assertEquals(koharia.domain.komga.KomgaReadStatusPhase.SYNCED, accepted.phase)
                        assertTrue(accepted.generation > checkNotNull(pending).generation)
                        assertNull(repository.attempt(key))
                        val local = checkNotNull(progress.getProgress(chapter.id))
                        assertEquals(java.util.Date(accepted.readAt), local.updatedAt)
                        assertEquals(local.updatedAt, local.lastSyncedAt)
                        assertEquals(0, model.currentOriginalPdfPage())
                        assertEquals(profile.id, model.state.value.sourceId)
                        assertEquals(chapter.id, model.state.value.chapterId)
                        assertTrue(server.writes.isEmpty())
                        assertTrue(pdfBytes.contentEquals(originalPdf.readBytes()))
                        assertTrue(derivedBytes.contentEquals(prepared.epub.readBytes()))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString("pdf_choice", "held-preserved;actual-remote-click;confirmed;writes=0")
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
                    assertEquals(null, repository.get(key))
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
                            while (repository.get(key)?.let {
                                    it.page == page.toLong() &&
                                        it.phase == koharia.domain.komga.KomgaReadStatusPhase.SYNCED
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
                        val state = checkNotNull(repository.get(key))
                        assertEquals(stored.updatedAt, stored.lastSyncedAt)
                        assertEquals(stored.updatedAt.time, state.readAt)
                        assertEquals(4L, state.total)
                        assertEquals(page.toLong(), state.page)
                        assertEquals(koharia.domain.komga.KomgaReadStatusPhase.SYNCED, state.phase)
                        assertTrue(
                            source.captureReflowProgressSession(
                                chapter,
                            ).adapter.isPageProgressConfirmed(url, page, 4, stored.updatedAt.time),
                        )
                        assertEquals(page + 1, checkNotNull(server.latest.get()).getInt("page"))
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString(
                                    "komga_pdf_saved",
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
                    for (write in server.writes) {
                        assertTrue(write.getInt("page") in 1..2)
                    }
                    if (offline) {
                        val before = checkNotNull(progress.getProgress(chapter.id))
                        val beforeState = checkNotNull(repository.get(key))
                        val remoteBefore = checkNotNull(server.latest.get()).toString()
                        val writesBefore = server.writes.size
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
                        assertEquals(beforeState, repository.get(key))
                        assertEquals(remoteBefore, checkNotNull(server.latest.get()).toString())
                        assertEquals(writesBefore, server.writes.size)
                        navigateToOriginal(2)
                        withContext(Dispatchers.Main) { model.saveCurrentProgress() }
                        withTimeout(45_000) {
                            while (repository.get(key)?.let {
                                    it.page == 2L && it.total == 4L && it.phase in setOf(
                                        koharia.domain.komga.KomgaReadStatusPhase.PENDING,
                                        koharia.domain.komga.KomgaReadStatusPhase.HELD,
                                    )
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
                    val pendingBefore = checkNotNull(repository.get(key))
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
                        assertEquals(pendingBefore, repository.get(key))
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
                val originalState = repository.get(key)
                configure()
                val failure = runCatching { captured.adapter.recordLocalPageProgress(chapter.url, 2, 4, 100) }
                assertTrue("Old captured session must reject writes", failure.isFailure)
                assertEquals(originalState, repository.get(key))
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
        val address = "http://127.0.0.1:${listener.localPort}"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pdf.readBytes()).joinToString("") {
            "%02x".format(it)
        }
        val available = java.util.concurrent.atomic.AtomicBoolean(true)
        val unavailableRequests = java.util.concurrent.atomic.AtomicInteger()
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
                            method == "PATCH" && path == "/api/v1/books/book/read-progress" -> {
                                val value = JSONObject(body.toString(Charsets.UTF_8))
                                writes += value
                                latest.set(
                                    JSONObject(value.toString())
                                        .put("completed", value.optBoolean("completed", false))
                                        .put("lastModified", java.time.Instant.now().toString()),
                                )
                                "{}"
                            }
                            method != "GET" -> {
                                unexpectedMutations += "$method $path"
                                code = 405
                                "{}"
                            }
                            path == "/api/v1/users/me" ->
                                """{"id":"fixture","email":"fixture@example.test","roles":["ADMIN","FILE_DOWNLOAD"]}"""
                            path == "/api/v1/books/book" -> {
                                val book =
                                    JSONObject(
                                        """{"id":"book","seriesId":"series","libraryId":"library",
                                    "name":"fixture.pdf","fileHash":"$hash","sizeBytes":${pdf.length()},
                                    "fileLastModified":"2026-01-01T00:00:00Z","metadata":{"title":"Layout book"},
                                    "media":{"status":"READY","mediaProfile":"PDF","mediaType":"application/pdf","pagesCount":4}}""",
                                    )
                                latest.get()?.let { book.put("readProgress", it) }
                                book.toString()
                            }
                            path == "/api/v1/libraries" -> "[]"
                            path == "/api/v1/books/book/file" -> ""
                            else -> {
                                code = 404
                                "{}"
                            }
                        }
                        val bytes = if (path == "/api/v1/books/book/file" &&
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
                                                "/api/v1/books/book/file"
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
