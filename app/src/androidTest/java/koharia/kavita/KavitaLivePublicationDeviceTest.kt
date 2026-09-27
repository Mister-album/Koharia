package koharia.kavita

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.kavita.KavitaRepository
import koharia.epub.EpubReaderFragment
import koharia.epub.EpubTransitionFixtureActivity
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.RemotePublicationRef
import koharia.epub.session.EpubReaderSession
import koharia.epub.session.EpubReaderSessionRepository
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.util.getOrElse
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Live content stays in the isolated fixture; its temporary connection and caches are removed. */
@RunWith(AndroidJUnit4::class)
class KavitaLivePublicationDeviceTest {
    @Test
    fun nativePublicationRenderingAndPersistentOfflineCache(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runKavitaLive") == "true")
        val credentials = OkHttpClient().newCall(
            Request.Builder().url(requireNotNull(arguments.getString("kavitaLiveBridge"))).build(),
        ).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
        val address = credentials.getValue("server").jsonPrimitive.content
        val key = credentials.getValue("key").jsonPrimitive.content
        require(!address.contains("demo.kavitareader.com", true))
        val manager: ConnectionProfileManager = Injekt.get()
        // Only this fixture's named connections may remain after an Android process crash.
        manager.profiles().filter {
            it.providerId == KavitaConnectionProvider.ID && it.name == "Kavita publication validation"
        }.forEach {
            manager.remove(it.id).getOrThrow()
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().deleteMangaBySourceId(it.id)
        }
        val profile = manager.add(KavitaConnectionProvider.ID, "Kavita publication validation")
        var source: KavitaSource? = null
        val downloadedOnly = Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly
        val wasSet = downloadedOnly.isSet()
        val previous = downloadedOnly.get()
        val incognito = Injekt.get<SharedAppPreferences>().basePreferences().incognitoMode
        val incognitoWasSet = incognito.isSet()
        val previousIncognito = incognito.get()
        val sessions: EpubReaderSessionRepository = Injekt.get()
        val localChapterId = -SystemClock.elapsedRealtimeNanos()
        try {
            KavitaApiClient(
                OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
                address,
                key,
                "device-discovery",
            ).use { api ->
                KavitaPreferences(profile.id).save(address, key, api.getAccount())
            }
            source = withTimeout(15_000) {
                var candidate: KavitaSource?
                do {
                    candidate = Injekt.get<SourceManager>().get(profile.id) as? KavitaSource
                    if (candidate == null) delay(100)
                } while (candidate == null)
                candidate
            }.also { it.reload() }
            val active = source
            downloadedOnly.set(false)
            incognito.set(true)
            stage("assertColdHistoryImport(active)")
            assertColdHistoryImport(active)
            stage("assertCanonicalQueue(active)")
            assertCanonicalQueue(active)
            stage("assertComic(active.session())")
            assertComic(active.session())
            stage("val pdfUrl = assertPdf(active)")
            val pdfUrl = assertPdf(active)
            stage("assertEpub(active, localChapterId)")
            assertEpub(active, localChapterId)
            assertNotNull(active.findCompletePdfFile(pdfUrl))
        } finally {
            sessions.remove(localChapterId)
            source?.close()
            if (wasSet) downloadedOnly.set(previous) else downloadedOnly.delete()
            if (incognitoWasSet) incognito.set(previousIncognito) else incognito.delete()
            manager.remove(profile.id).getOrThrow()
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().deleteMangaBySourceId(profile.id)
        }
    }

    private suspend fun assertColdHistoryImport(source: KavitaSource) {
        val mangas: tachiyomi.domain.manga.repository.MangaRepository = Injekt.get()
        val chapters: tachiyomi.domain.chapter.repository.ChapterRepository = Injekt.get()
        val getHistory: tachiyomi.domain.history.interactor.GetHistory = Injekt.get()
        assertTrue(mangas.getMangaBySourceId(source.id).isEmpty())
        val remote = source.session().catalog.history().ifEmpty {
            val ref = sample(source.session(), 3).first
            // This server has no completed reading sessions; use an explicit event fixture with real content.
            listOf(KavitaHistoryEntry(ref.libraryId, ref.seriesId, ref.chapterId, System.currentTimeMillis() - 60_000))
        }
        source.syncConnectionHistory()
        source.importHistorySnapshot(source.session(), remote)
        var imported = 0
        for (manga in mangas.getMangaBySourceId(source.id)) {
            val timestamps = getHistory.await(manga.id).associate { it.chapterId to it.readAt?.time }
            for (chapter in chapters.getChapterByMangaId(manga.id)) {
                val ref = source.session().identity.chapter(chapter.url)
                val expected = remote.firstOrNull { it.chapterId == ref.chapterId } ?: continue
                assertTrue((timestamps[chapter.id] ?: 0) >= expected.readAt)
                imported++
            }
        }
        assertTrue("History snapshot must materialize an unindexed chapter", imported > 0)
        val firstManga = mangas.getMangaBySourceId(source.id).first { getHistory.await(it.id).isNotEmpty() }
        val first = getHistory.await(firstManga.id).first()
        val newer = requireNotNull(first.readAt).time + java.util.concurrent.TimeUnit.DAYS.toMillis(1)
        Injekt.get<tachiyomi.domain.history.interactor.UpsertHistory>().await(
            tachiyomi.domain.history.model.HistoryUpdate(first.chapterId, java.util.Date(newer), 0),
        )
        source.syncConnectionHistory()
        source.importHistorySnapshot(source.session(), remote)
        val retained = getHistory.await(firstManga.id).single { it.chapterId == first.chapterId }
        assertEquals(newer, retained.readAt?.time)
        assertEquals(first.readDuration, retained.readDuration)
        val repository: KavitaRepository = Injekt.get()
        assertTrue(repository.operations(source.id, source.session().accountKey).none { it.pending })
    }

    private suspend fun assertCanonicalQueue(source: KavitaSource) {
        val refs = listOf(3, 4, 1).map { sample(source.session(), it).first }
        val queue = source.createReadingQueue(
            "Mixed media fixture",
            refs.mapIndexed { index, ref ->
                KavitaListItem(
                    order = index,
                    chapterId = ref.chapterId,
                    seriesId = ref.seriesId,
                    volumeId = ref.volumeId,
                    libraryId = ref.libraryId,
                    seriesFormat = ref.format,
                )
            },
        )
        val chapters: tachiyomi.domain.chapter.repository.ChapterRepository = Injekt.get()
        val mangas: tachiyomi.domain.manga.repository.MangaRepository = Injekt.get()
        val resolved = refs.map { ref ->
            val url = source.session().identity.chapter(ref)
            source.resolveReadingQueueChapter(queue, url).also { item ->
                val manga = requireNotNull(mangas.getMangaById(item.mangaId))
                assertEquals(source.session().identity.series(ref.seriesId), manga.url)
                assertEquals(url, chapters.getChapterByMangaId(item.mangaId).single { it.id == item.chapterId }.url)
            }
        }
        source.reload()
        resolved.forEachIndexed { index, chapter ->
            val position = source.readingQueuePosition(queue, chapter.chapterUrl)
            assertEquals(index, position.index)
            assertEquals(resolved.getOrNull(index + 1)?.chapterUrl, position.next)
            assertEquals(chapter, source.resolveReadingQueueChapter(queue, chapter.chapterUrl))
        }
        assertQueueActivities(source, queue, resolved)
    }

    private suspend fun assertQueueActivities(
        source: KavitaSource,
        queue: String,
        chapters: List<koharia.connection.ConnectionReadingQueueChapter>,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(Injekt.get<koharia.connection.SharedConfigMigration>().isPending())
        val intents = chapters.map {
            koharia.epub.EpubReaderLauncher().resolveIntent(context, it.mangaId, it.chapterId)
        }
        val first = koharia.connection.ConnectionReadingQueueController.attach(
            intents.first(),
            source.id,
            queue,
            chapters.first().chapterUrl,
        )
        val opened = mutableListOf<android.app.Activity>()
        ActivityScenario.launch<androidx.activity.ComponentActivity>(first).use { scenario ->
            var current: androidx.activity.ComponentActivity? = null
            scenario.onActivity { current = it }
            try {
                assertContentTapHidesMenu(requireNotNull(current))
                for (index in listOf(1, 2, 1, 0)) {
                    val previous = requireNotNull(current)
                    val target = navigateQueueActivity(
                        previous,
                        index > chapters.indexOfFirst {
                            it.chapterId == previous.intent.getLongExtra("chapter", -1)
                        },
                        requireNotNull(intents[index].component).className,
                    )
                    opened += target
                    current = target
                    assertEquals(chapters[index].chapterId, target.intent.getLongExtra("chapter", -1))
                    assertEquals(chapters[index].mangaId, target.intent.getLongExtra("manga", -1))
                    assertEquals(
                        queue,
                        target.intent.getStringExtra(koharia.connection.ConnectionReadingQueueController.CONTEXT),
                    )
                }
            } finally {
                instrumentation.runOnMainSync { opened.forEach { if (!it.isFinishing) it.finish() } }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun stage(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply {
                putString("stream", "\n$name\n")
            },
        )
    }

    private suspend fun assertContentTapHidesMenu(activity: androidx.activity.ComponentActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = withContext(Dispatchers.Main) {
            androidx.lifecycle.ViewModelProvider(activity)[koharia.epub.EpubReaderViewModel::class.java]
        }
        withTimeout(30_000) { while (!model.state.value.isReady) delay(100) }
        val navigator = withTimeout(30_000) {
            var found: EpubNavigatorFragment? = null
            while (found == null) {
                withContext(Dispatchers.Main) {
                    val fragment = (activity as androidx.fragment.app.FragmentActivity)
                        .supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>().firstOrNull()
                    found = fragment?.childFragmentManager?.fragments
                        ?.filterIsInstance<EpubNavigatorFragment>()?.firstOrNull()
                }
                if (found == null) delay(100)
            }
            requireNotNull(found)
        }
        withContext(Dispatchers.Main) {
            navigator.go(
                requireNotNull(
                    Injekt.get<koharia.epub.session.EpubReaderSessionRepository>().get(model.state.value.chapterId),
                ).publication.readingOrder[1],
            )
            model.dismissServerTimeWarning()
            model.showMenus(true)
        }
        delay(1000)
        withContext(Dispatchers.Main) {
            model.dismissServerTimeWarning()
            if (model.state.value.remoteProgressConflict != null) model.keepLocalProgress()
        }
        delay(300)
        val point = withContext(Dispatchers.Main) {
            val view = navigator.publicationView
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            (location[0] + view.width * 0.5f) to (location[1] + view.height * 0.5f)
        }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(
                now,
                SystemClock.uptimeMillis(),
                action,
                point.first,
                point.second,
                0,
            )
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        val hidden = kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (model.state.value.menuVisible) delay(100)
            true
        } ?: false
        assertTrue("Content tap must hide reader menus", hidden)
        stage("Content tap passed")
    }

    private suspend fun navigateQueueActivity(
        current: androidx.activity.ComponentActivity,
        forward: Boolean,
        expectedClass: String,
    ): androidx.activity.ComponentActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(expectedClass, null, false)
        try {
            val controller = withContext(Dispatchers.Main) {
                koharia.connection.ConnectionReadingQueueController(current).also { it.start() }
            }
            withTimeout(15_000) {
                while (withContext(Dispatchers.Main) { controller.position == null }) delay(100)
            }
            withContext(Dispatchers.Main) { controller.navigate(forward) }
            val opened = instrumentation.waitForMonitorWithTimeout(monitor, 30_000)
            assertNotNull("Queue must start $expectedClass", opened)
            instrumentation.waitForIdleSync()
            return opened as androidx.activity.ComponentActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private suspend fun sample(session: KavitaSource.Session, format: Int): Pair<KavitaChapterRef, KavitaChapter> {
        val filter = KavitaFilter(listOf(KavitaFilterStatement(21, 0, format.toString())))
        val series = session.catalog.page(1, filter).items.first()
        val volume = session.catalog.volumes(series.id).first { it.chapters.isNotEmpty() }
        val chapter = volume.chapters.first()
        return KavitaChapterRef(series.libraryId, series.id, volume.id, chapter.id, format) to chapter
    }

    private suspend fun assertComic(session: KavitaSource.Session) {
        val (ref, _) = sample(session, 1)
        session.api.client.newCall(Request.Builder().url(session.api.page(ref.chapterId, 0)).build())
            .execute().use { response ->
                assertEquals(200, response.code)
                val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                assertNotNull("Server comic page must decode on Android", bitmap)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            }
    }

    private suspend fun assertPdf(source: KavitaSource): String {
        val (ref, chapter) = sample(source.session(), 4)
        val url = source.session().identity.chapter(ref)
        val file = source.preparePdfFile(url)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.openFileDescriptor(file.uri, "r")!!.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(chapter.pages, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                }
            }
        }
        return url
    }

    private suspend fun assertEpub(source: KavitaSource, localChapterId: Long) {
        val session = source.session()
        val sessions: EpubReaderSessionRepository = Injekt.get()
        val ref = styledBook(session)
        val bookUrl = session.identity.chapter(ref)
        val open = EpubOpenRequest(
            0,
            localChapterId,
            source.id,
            "Kavita publication test",
            RemotePublicationRef(KavitaConnectionProvider.ID, bookUrl),
            null,
            EpubOpenRequest.OpenSource.REMOTE,
            publicationKey = bookUrl,
        )
        val book = source.openRemotePublication(open, null)
        sessions.put(book)
        val pageCount = session.catalog.book(ref.chapterId).pages
        assertTrue(book.publication.conformsTo(org.readium.r2.shared.publication.Publication.Profile.EPUB))
        assertEquals(pageCount, book.publication.readingOrder.size)
        for (link in book.publication.readingOrder) {
            val resource = requireNotNull(book.publication.get(link))
            try {
                val bytes = resource.read().getOrElse { error("Readium content resource failed") }
                assertTrue(bytes.isNotEmpty())
                assertTrue(bytes.toString(Charsets.UTF_8).contains("data-koharia-kavita-path"))
            } finally {
                resource.close()
            }
        }
        val firstHtml = requireNotNull(book.publication.get(book.publication.readingOrder.first())).read()
            .getOrElse { error("First page failed: $it") }.toString(Charsets.UTF_8)
        val imageUrl = org.jsoup.Jsoup.parse(firstHtml).selectFirst("img")!!.attr("src")
        val imageResource =
            requireNotNull(book.publication.get(requireNotNull(org.readium.r2.shared.util.Url(imageUrl))))
        val imageBytes = imageResource.read().getOrElse { error("Publication image failed: $it") }
        imageResource.close()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        assertNotNull("Image bytes must decode, size=${imageBytes.size}", bitmap)
        bitmap?.recycle()
        stage("assertReadiumAnchorRoundTrip(book)")
        assertReadiumAnchorRoundTrip(book)
        sessions.remove(localChapterId)
        // Recreate the catalog over real SQLDelight data, then disable its transport.
        source.reload()
        val restarted = source.session()
        restarted.api.close()
        Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly.set(true)
        val offline = source.openRemotePublication(open, null)
        sessions.put(offline)
        assertEquals(pageCount, offline.publication.readingOrder.size)
        stage("assertReadiumAnchorRoundTrip(offline)")
        assertReadiumAnchorRoundTrip(offline)
        val repository: KavitaRepository = Injekt.get()
        assertNotNull(repository.cache(source.id, restarted.accountKey, "libraries", "data"))
    }

    private suspend fun styledBook(session: KavitaSource.Session): KavitaChapterRef {
        for (series in session.catalog.page(
            1,
            KavitaFilter(listOf(KavitaFilterStatement(21, 0, "3"))),
        ).items.take(10)) {
            val volume = session.catalog.volumes(series.id).first { it.chapters.isNotEmpty() }
            val chapter = volume.chapters.first()
            val html = session.api.client.newCall(
                session.api.request("Book/${chapter.id}/book-page", "page" to 0),
            ).execute().use {
                it.body.string()
            }
            if (html.contains("@import") &&
                html.contains("<img")
            ) {
                return KavitaChapterRef(series.libraryId, series.id, volume.id, chapter.id, 3)
            }
        }
        error("Live fixture needs an EPUB with imported styles and an embedded cover image")
    }

    private suspend fun assertReadiumAnchorRoundTrip(session: EpubReaderSession) {
        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var fragment: EpubReaderFragment
            scenario.onActivity {
                fragment = EpubReaderFragment.newInstance(session.chapterId, -1)
                it.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            }
            val navigator = withTimeout(30_000) {
                var found: EpubNavigatorFragment? = null
                while (found == null) {
                    scenario.onActivity {
                        val children = fragment.childFragmentManager.fragments
                        found = children.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
                    }
                    delay(100)
                }
                requireNotNull(found)
            }
            stage("Wait for DOM or imported stylesheet")
            withTimeout(30_000) {
                while (withContext(Dispatchers.Main) {
                        navigator.evaluateJavascript(
                            "document.querySelectorAll('[data-koharia-kavita-path]').length > 0",
                        )
                    } != "true"
                ) {
                    delay(100)
                }
            }
            stage("Wait for images")
            withTimeout(30_000) {
                while (withContext(Dispatchers.Main) {
                        navigator.evaluateJavascript(
                            "Array.from(document.images).some(i => i.complete && i.naturalWidth > 0)",
                        )
                    } != "true"
                ) {
                    delay(100)
                }
            }
            stage("Wait for DOM or imported stylesheet")
            withTimeout(30_000) {
                while (withContext(Dispatchers.Main) {
                        navigator.evaluateJavascript(
                            "Array.from(document.styleSheets).some(s => { try { return Array.from(s.cssRules).some(r => r.type === 3 && r.styleSheet && r.styleSheet.cssRules.length > 0); } catch(e) { return false; } })",
                        )
                    } != "true"
                ) {
                    delay(100)
                }
            }
            val locator = withContext(Dispatchers.Main) {
                captureKavitaLocator(navigator, navigator.currentLocator.value)
            }
            assertTrue(locator.toJSON().getJSONObject("locations").optString("kavitaXPath").startsWith("//body"))
            assertEquals(true, withContext(Dispatchers.Main) { restoreKavitaLocator(navigator, locator) })
            var hasSelection = false
            for (link in session.publication.readingOrder.take(12)) {
                withContext(Dispatchers.Main) { navigator.go(link) }
                delay(250)
                hasSelection = withContext(Dispatchers.Main) {
                    navigator.evaluateJavascript(KAVITA_SELECTION_SCRIPT)
                    navigator.evaluateJavascript(
                        """
                        (function() {
                            const el = Array.from(document.querySelectorAll('[data-koharia-kavita-path]'))
                                .find(n => n.children.length === 0 && n.textContent.trim().length > 8 &&
                                    !n.matches('style,script'));
                            if (!el) return false;
                            const range = document.createRange(); range.selectNodeContents(el);
                            const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                            return true;
                        })()
                        """.trimIndent(),
                    ) == "true"
                }
                if (hasSelection) break
            }
            assertTrue("Fixture needs selectable EPUB text", hasSelection)
            delay(100)
            val selection = withContext(Dispatchers.Main) { captureKavitaSelection(navigator) }
            assertNotNull(selection)
            withContext(Dispatchers.Main) {
                renderKavitaHighlights(
                    navigator,
                    listOf(koharia.connection.ConnectionTextHighlight(requireNotNull(selection), 1)),
                )
                assertEquals(
                    "true",
                    navigator.evaluateJavascript(
                        "CSS.highlights.get('koharia-annotation-1').size > 0",
                    ),
                )
            }
        }
    }
}
