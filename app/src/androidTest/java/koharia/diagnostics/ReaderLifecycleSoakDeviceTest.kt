package koharia.diagnostics

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.epub.EpubReaderActivity
import koharia.epub.EpubReaderFragment
import koharia.epub.EpubReaderLauncher
import koharia.epub.EpubReaderViewModel
import koharia.epub.settings.EpubLayoutPreferences
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicitly selected local reader observation; completion is not a performance acceptance gate. */
@RunWith(AndroidJUnit4::class)
class ReaderLifecycleSoakDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pid = Process.myPid()
    private val observedObjects = mutableListOf<ObservedObject>()
    private val observeClose = InstrumentationRegistry.getArguments().getString("soakObserveClose") == "true"
    private val userExit = InstrumentationRegistry.getArguments().getString("soakUserExit") == "true"

    @Test
    fun repeatedLocalComicPdfAndEpubReading() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("readerLifecycleSoak") == "true")
        check(!userExit || observeClose)
        val rounds = checkNotNull(arguments.getString("soakRounds")).toInt().also { check(it in 1..30) }
        val holdSeconds = checkNotNull(arguments.getString("soakHoldSeconds")).toInt().also { check(it in 1..120) }
        val kinds = if (arguments.getString("soakOnlyEpub") == "true") {
            listOf("SoakBook")
        } else {
            listOf("SoakComic", "SoakPdf", "SoakBook")
        }
        readMedia(rounds, holdSeconds, kinds)
    }

    @Test
    fun paginationCompletionPreservesInFlightNavigation() =
        readMedia(1, 1, listOf("SoakBook"), verifyPaginationCompletion = true)

    private fun readMedia(
        rounds: Int,
        holdSeconds: Int,
        kinds: List<String>,
        verifyPaginationCompletion: Boolean = false,
    ): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previousActive = connections.activeConnectionId.get()
        val profile = profiles.add("local-folder", "Reader lifecycle fixture")
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val root = File(context.cacheDir, "reader-soak-${UUID.randomUUID()}")
        check(root.mkdirs())
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        try {
            val shared = Injekt.get<SharedAppPreferences>()
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().incognitoMode, false)
            val reader = shared.readerPreferences()
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            replace(shared.epubReaderPreferences().syncRemoteProgression, false)
            if (verifyPaginationCompletion) {
                val layout = shared.epubLayoutPreferences()
                replace(layout.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
                replace(layout.pageDirection, EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT)
                replace(
                    layout.pageTransitionEffect,
                    eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect.NONE.value,
                )
            }
            createMedia(root)
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            LocalLibraryPreferences(profile.id, Json).setConfig(
                config.copy(
                    setupCompleted = true,
                    roots = listOf("comics" to LocalLibraryContentType.COMICS, "books" to LocalLibraryContentType.BOOKS)
                        .map { (name, contentType) ->
                            LocalLibraryRootConfig(
                                id = UUID.randomUUID().toString(),
                                treeUri = File(root, name).toURI().toString(),
                                contentType = contentType,
                                bookshelfId = config.defaultBookshelfId(contentType),
                            )
                        },
                ),
            )
            withTimeout(15_000) { while (Injekt.get<SourceManager>().get(profile.id) == null) delay(50) }
            val source = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            withTimeout(30_000) { source.refreshLibrary().getOrThrow() }
            val entries = mangas.getMangaBySourceId(profile.id)
            check(entries.size == 3) { "Expected exactly three owned media entries" }
            val units = entries.associate { manga ->
                Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
                manga.title to (manga.id to chapters.getChapterByMangaId(manga.id).single().id)
            }
            check(units.keys == setOf("SoakComic", "SoakPdf", "SoakBook"))
            snapshot(0, "all", "prepared")
            repeat(rounds) { round ->
                for (kind in kinds) {
                    val (mangaId, chapterId) = units.getValue(kind)
                    val frames = Frames()
                    if (kind == "SoakBook") {
                        FixtureActivityLauncher.launch<EpubReaderActivity>(
                            EpubReaderLauncher().resolveIntent(context, mangaId, chapterId),
                        ).useObserved(round + 1, kind) { scenario ->
                            scenario.onActivity { frames.attach(it.window) }
                            try {
                                awaitText("Soak section 1", scenario)
                                if (verifyPaginationCompletion) verifyPaginationCompletion(scenario)
                                scenario.onActivity { observeReader(round + 1, kind, it) }
                                snapshot(round + 1, kind, "opened")
                                val deadline = SystemClock.elapsedRealtime() + holdSeconds * 1_000L
                                do {
                                    navigationStatus("before_forward", scenario)
                                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                                    navigationStatus("after_forward_input", scenario)
                                    awaitText("Soak section 2", scenario)
                                    navigationStatus("before_backward", scenario)
                                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                                    navigationStatus("after_backward_input", scenario)
                                    awaitText("Soak section 1", scenario)
                                    delay(1_000)
                                } while (SystemClock.elapsedRealtime() < deadline)
                                snapshot(round + 1, kind, "held", frames)
                            } finally {
                                scenario.onActivity { frames.detach(it.window) }
                            }
                        }
                    } else {
                        FixtureActivityLauncher.launch<ReaderActivity>(
                            ReaderActivity.newIntent(context, mangaId, chapterId, profile.id, pageIndex = 0),
                        ).useObserved(round + 1, kind) { scenario ->
                            scenario.onActivity { frames.attach(it.window) }
                            suspend fun page(index: Int) {
                                withTimeout(25_000) {
                                    while (true) {
                                        var ready = false
                                        scenario.onActivity { activity ->
                                            val state = activity.viewModel.state.value
                                            ready = state.totalPages == 3 && state.currentPage == index + 1 &&
                                                state.currentChapter?.pages?.getOrNull(index)?.status ==
                                                Page.State.Ready &&
                                                descendants(activity.window.decorView)
                                                    .filterIsInstance<SubsamplingScaleImageView>().any { it.isReady }
                                        }
                                        if (ready) break
                                        delay(50)
                                    }
                                }
                            }
                            try {
                                page(0)
                                awaitRedPage()
                                scenario.onActivity { observeReader(round + 1, kind, it) }
                                snapshot(round + 1, kind, "opened")
                                val deadline = SystemClock.elapsedRealtime() + holdSeconds * 1_000L
                                do {
                                    for (index in listOf(1, 2, 0)) {
                                        scenario.onActivity { activity ->
                                            val state = activity.viewModel.state.value
                                            state.viewer!!.moveToPage(state.currentChapter!!.pages!![index])
                                        }
                                        page(index)
                                        delay(350)
                                    }
                                } while (SystemClock.elapsedRealtime() < deadline)
                                awaitRedPage()
                                snapshot(round + 1, kind, "held", frames)
                            } finally {
                                scenario.onActivity { frames.detach(it.window) }
                            }
                        }
                    }
                    delay(2_000)
                    snapshot(round + 1, kind, "closed")
                }
            }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
                mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
            } finally {
                connections.activeConnectionId.set(previousActive)
                restores.asReversed().forEach { it() }
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(root.deleteRecursively())
            }
        }
        snapshot(rounds, "all", "cleaned")
    }

    private suspend fun <A : Activity> ActivityScenario<A>.useObserved(
        round: Int,
        kind: String,
        action: suspend (ActivityScenario<A>) -> Unit,
    ) {
        if (!observeClose) {
            use { action(it) }
            return
        }
        val events = ConcurrentLinkedQueue<JSONObject>()
        val stacks = ConcurrentLinkedQueue<JSONObject>()
        onActivity { activity ->
            val reference = WeakReference(activity)
            (activity as LifecycleOwner).lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    events += JSONObject().put("event", event.name).put("uptime_ms", SystemClock.uptimeMillis())
                        .put("finishing", reference.get()?.isFinishing)
                },
            )
        }
        val finished = CountDownLatch(1)
        var watcher: Thread? = null
        var startedAt = 0L
        var closed = false
        try {
            use { scenario ->
                try {
                    action(scenario)
                } finally {
                    startedAt = SystemClock.uptimeMillis()
                    watcher = Thread {
                        while (!finished.await(1, TimeUnit.SECONDS) && stacks.size < 45) {
                            stacks += JSONObject().put("elapsed_ms", SystemClock.uptimeMillis() - startedAt)
                                .put(
                                    "main_stack",
                                    JSONArray(Looper.getMainLooper().thread.stackTrace.take(30).map { it.toString() }),
                                )
                        }
                    }.apply {
                        name = "ReaderCloseObservation"
                        isDaemon = true
                        start()
                    }
                }
                if (userExit) {
                    key(KeyEvent.KEYCODE_BACK)
                    withTimeout(30_000) {
                        while (scenario.state != Lifecycle.State.DESTROYED) delay(50)
                    }
                }
            }
            closed = true
        } finally {
            val elapsed = SystemClock.uptimeMillis() - startedAt
            finished.countDown()
            watcher?.join(2_000)
            val data = JSONObject().put("round", round).put("kind", kind).put("closed", closed)
                .put("exit_mode", if (userExit) "back_key_then_scenario_cleanup" else "scenario_close")
                .put("close_ms", elapsed).put("events", JSONArray(events.toList()))
                .put("slow_close_stacks", JSONArray(stacks.toList()))
            instrumentation.sendStatus(2, Bundle().apply { putString("reader_close", data.toString()) })
        }
    }

    private fun snapshot(round: Int, kind: String, phase: String, frames: Frames? = null) {
        check(Process.myPid() == pid)
        val retained = JSONArray()
        instrumentation.runOnMainSync {
            for (observed in observedObjects) {
                val value = observed.reference.get() ?: continue
                if (phase == "closed" || phase == "cleaned") {
                    check(value !is Activity || value.isDestroyed) { "Reader activity did not reach destruction" }
                }
                retained.put(
                    JSONObject().put("round", observed.round).put("kind", observed.kind).put("role", observed.role),
                )
            }
        }
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val data = JSONObject()
            .put("pid", pid)
            .put("round", round).put("kind", kind).put("phase", phase)
            .put("elapsed_ms", SystemClock.elapsedRealtime()).put("process_cpu_ms", Process.getElapsedCpuTime())
            .put("fd_count", checkNotNull(File("/proc/self/fd").list()).size)
            .put("pss_kb", memory.totalPss).put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            .put("gc_count", Debug.getRuntimeStat("art.gc.gc-count"))
            .put("observed_object_count", observedObjects.size)
            .put("retained_reader_objects", retained)
            .put(
                "threads",
                File("/proc/self/status").useLines { lines ->
                    lines.first { it.startsWith("Threads:") }.substringAfter(':').trim().toInt()
                },
            )
        frames?.append(data)
        instrumentation.sendStatus(2, Bundle().apply { putString("reader_soak", data.toString()) })
    }

    private fun observeReader(round: Int, kind: String, activity: Activity) {
        fun observe(role: String, value: Any) {
            observedObjects += ObservedObject(round, kind, role, WeakReference(value))
        }
        observe("activity", activity)
        observe("window", activity.window)
        observe("decor", activity.window.decorView)
        when (activity) {
            is ReaderActivity -> {
                observe("view_model", activity.viewModel)
                activity.viewModel.state.value.viewer?.let { observe("viewer", it) }
            }
            is EpubReaderActivity -> {
                observe("view_model", ViewModelProvider(activity)[EpubReaderViewModel::class.java])
                activity.supportFragmentManager.fragments.forEach { fragment ->
                    observe("fragment", fragment)
                    fragment.childFragmentManager.fragments.forEach { observe("navigator", it) }
                }
                descendants(activity.window.decorView).filterIsInstance<WebView>().forEach { observe("webview", it) }
            }
        }
    }

    private data class ObservedObject(
        val round: Int,
        val kind: String,
        val role: String,
        val reference: WeakReference<Any>,
    )

    private class Frames : Window.OnFrameMetricsAvailableListener {
        private val values = mutableListOf<Long>()
        private var missed = 0
        fun attach(window: Window) = window.addOnFrameMetricsAvailableListener(this, Handler(Looper.getMainLooper()))
        fun detach(window: Window) = window.removeOnFrameMetricsAvailableListener(this)

        @Synchronized
        override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
            if (values.size < 10_000) values += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            missed += dropCountSinceLastInvocation
        }

        @Synchronized
        fun append(data: JSONObject) {
            val sorted = values.sorted()
            data.put("frame_samples", sorted.size).put("frame_reports_dropped", missed)
            if (sorted.isNotEmpty()) {
                data.put("frame_total_p95_ns", sorted[(sorted.lastIndex * 0.95).toInt()])
                data.put("frame_total_max_ns", sorted.last())
            }
        }
    }

    private fun key(code: Int) {
        instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON))
        instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_CTRL_ON))
    }

    private suspend fun verifyPaginationCompletion(scenario: ActivityScenario<EpubReaderActivity>) {
        for (forward in listOf(true, false)) {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EpubReaderViewModel::class.java]
                val fragment = activity.supportFragmentManager.fragments.filterIsInstance<EpubReaderFragment>().single()
                fragment.stopPagination()
                // A user turn also supersedes an explicit layout restoration still waiting for counts.
                val readingRevision = model.readingNavigationRevision
                model.onLayoutPreferencesChanged()
                check(model.takeLayoutRestoreLocator(model.state.value.paginationGeneration - 1) == null)
                check(if (forward) fragment.goForward() else fragment.goBackward())
                // An anchor capture completing after that turn must not re-arm the old position.
                model.onLayoutPreferencesChanged(readingRevision)
                val controller = EpubReaderFragment::class.java.getDeclaredField("pageTransitionController")
                    .apply { isAccessible = true }.get(fragment)!!
                val generation = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
                val before = generation.getLong(controller)
                activity.onBookPaginationChanged(
                    model.state.value.paginationGeneration,
                    mapOf("s1.xhtml" to 1, "s2.xhtml" to 1, "s3.xhtml" to 1),
                    isComplete = true,
                )
                check(generation.getLong(controller) == before) {
                    "Background pagination completion cancelled the in-flight reader navigation"
                }
            }
            awaitText("Soak section ${if (forward) 2 else 1}", scenario)
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[EpubReaderViewModel::class.java].state.value
                check(state.currentVisualPage == (if (forward) 2 else 1) && state.totalVisualPages == 3) {
                    "Pagination counts must update without repositioning the reader"
                }
            }
        }
    }

    private suspend fun awaitText(expected: String, scenario: ActivityScenario<EpubReaderActivity>) {
        val expectedHref = "s${expected.removePrefix("Soak section ").toInt()}.xhtml"
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.text?.contains(expected) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        val ready = withTimeoutOrNull(25_000) {
            while (true) {
                var locationReady = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[EpubReaderViewModel::class.java]
                    val fragment = activity.supportFragmentManager.fragments
                        .filterIsInstance<EpubReaderFragment>().singleOrNull()
                    if (fragment != null) {
                        fun field(name: String) = EpubReaderFragment::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(fragment)
                        locationReady = model.currentLocator()?.href?.toString() == expectedHref &&
                            field("currentTransitionHref") == expectedHref &&
                            (field("currentTransitionPageCount") as Int) > 0
                    }
                }
                if (locationReady && contains(instrumentation.uiAutomation.rootInActiveWindow)) break
                delay(100)
            }
            true
        } == true
        if (!ready) navigationStatus("timeout_expected_$expected", scenario)
        check(ready) { "EPUB did not render $expected within 25000ms; see reader_soak_navigation" }
    }

    private fun navigationStatus(stage: String, scenario: ActivityScenario<EpubReaderActivity>) {
        val data = JSONObject().put("stage", stage).put("elapsed_ms", SystemClock.elapsedRealtime())
        val layout = Injekt.get<SharedAppPreferences>().epubLayoutPreferences()
        data.put("epub_mode", layout.readingMode.get().name)
            .put("epub_effect", layout.pageTransitionEffect.get())
            .put("animators_enabled", android.animation.ValueAnimator.areAnimatorsEnabled())
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[EpubReaderViewModel::class.java]
            data.put("ready", model.state.value.isReady)
                .put("href", model.currentLocator()?.href?.toString())
                .put("pagination_phase", model.state.value.paginationPhase.name)
                .put("pagination_generation", model.state.value.paginationGeneration)
                .put("visual_page", model.state.value.currentVisualPage)
                .put("visual_total", model.state.value.totalVisualPages)
            val fragment = activity.supportFragmentManager.fragments
                .filterIsInstance<EpubReaderFragment>().singleOrNull()
            if (fragment != null) {
                for (name in listOf(
                    "currentTransitionPageIndex",
                    "currentTransitionPageCount",
                    "currentTransitionHref",
                    "lastReadingInteraction",
                    "continuousScrollInstalledHref",
                )) {
                    val field = EpubReaderFragment::class.java.getDeclaredField(name).apply { isAccessible = true }
                    data.put(name, field.get(fragment))
                }
                val navigator = EpubReaderFragment::class.java.getDeclaredField("observedNavigator")
                    .apply { isAccessible = true }.get(fragment) as? EpubNavigatorFragment
                data.put("navigator_href", navigator?.currentLocator?.value?.href?.toString())
                val continuous = EpubReaderFragment::class.java.getDeclaredField("continuousScrollLocator")
                    .apply { isAccessible = true }.get(fragment) as? Locator
                data.put("continuous_href", continuous?.href?.toString())
                val controller = EpubReaderFragment::class.java.getDeclaredField("pageTransitionController")
                    .apply { isAccessible = true }.get(fragment)
                if (controller != null) {
                    for (name in listOf("generation", "active", "pendingTurns")) {
                        val value = controller.javaClass.getDeclaredField(name)
                            .apply { isAccessible = true }.get(controller)
                        data.put(
                            "transition_$name",
                            when (value) {
                                is Number -> value
                                is Collection<*> -> value.size
                                else -> value != null
                            },
                        )
                    }
                }
            }
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("reader_soak_navigation", data.toString()) })
    }

    private suspend fun awaitRedPage() = withTimeout(25_000) {
        while (true) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                try {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    if (Color.red(color) >= 247 && Color.green(color) <= 8 && Color.blue(color) <= 8) break
                } finally {
                    bitmap.recycle()
                }
            }
            delay(100)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    private fun createMedia(root: File) {
        val comic = File(root, "comics/SoakComic/book.cbz").apply { parentFile!!.mkdirs() }
        ZipOutputStream(comic.outputStream()).use { zip ->
            repeat(3) { index ->
                val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                    zip.putNextEntry(ZipEntry("$index.png"))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val pdf = File(root, "comics/SoakPdf/book.pdf").apply { parentFile!!.mkdirs() }
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val epub = File(root, "books/SoakBook.epub").apply { parentFile!!.mkdirs() }
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
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
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">reader-soak</dc:identifier>
                <dc:title>SoakBook</dc:title><dc:language>en</dc:language></metadata>
                <manifest>$manifest</manifest><spine>$spine</spine></package>
                """.trimIndent(),
            )
            for (section in 1..3) {
                entry(
                    "s$section.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Section $section</title></head>
                    <body><p>Soak section $section</p></body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
