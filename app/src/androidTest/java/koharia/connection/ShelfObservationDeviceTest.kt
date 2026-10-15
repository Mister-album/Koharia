package koharia.connection

import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class ShelfObservationDeviceTest {
    @Test
    fun sqlWindowIsConnectionScopedAndSupportsParameterChunks(): Unit = fixture(1_002) { id, mangas, chapters ->
        val repository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        assertTrue(repository.getMangaByUrlsAndSourceIdAsFlow(emptyList(), id).first().isEmpty())
        assertTrue(repository.getMangaByUrlsAndSourceIdAsFlow(listOf(mangas.first().url), id + 1).first().isEmpty())
        val selected = repository.getMangaByUrlsAndSourceIdAsFlow(
            mangas.map {
                it.url
            } + mangas.first().url,
            id,
        ).first()
        assertEquals(mangas.map { it.id }.toSet(), selected.map { it.id }.toSet())
        val units = chapterRepository.getChaptersByMangaIdsAsFlow(mangas.map { it.id } + mangas.first().id).first()
        assertEquals(chapters.map { it.id }.toSet(), units.map { it.id }.toSet())
        assertEquals(1, repository.getMangaByUrlsAndSourceIdAsFlow(listOf(mangas.last().url), id).first().size)
        val flow = chapterRepository.getChaptersByMangaIdsAsFlow(listOf(mangas.last().id))
        chapterRepository.update(ChapterUpdate(chapters.last().id, read = true))
        assertEquals(listOf(true), flow.first().map { it.read })
        val plan = Injekt.get<SqlDriver>().executeQuery(
            null,
            "EXPLAIN QUERY PLAN SELECT * FROM mangas INDEXED BY mangas_source_url_index " +
                "WHERE source = $id AND url IN ('shelf-fixture/1', 'shelf-fixture/999') ORDER BY _id",
            { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(3).orEmpty()) })
            },
            0,
        ).await()
        assertTrue(plan.toString(), plan.any { "mangas_source_url_index" in it })
    }

    @Test
    fun actualListAndGridKeepOnlyViewportAndPrefetchEntriesObserved(): Unit = fixture(600) { id, mangas, _ ->
        val source = KomgaSource(id)
        var mode by mutableIntStateOf(0)
        val result = AtomicReference<Map<String, ConnectionShelfEntryState>>(emptyMap())
        val visible = AtomicReference<List<Int>>(emptyList())
        val rendered = java.util.concurrent.ConcurrentHashMap<Long, Manga>()
        val positions = java.util.concurrent.ConcurrentHashMap<Long, Offset>()
        val clicked = AtomicReference<Manga?>(null)
        val animationContext = AtomicReference<CoroutineContext>()
        var list: LazyListState? = null
        var grid: LazyGridState? = null
        var displayed by mutableIntStateOf(1)
        val measureScroll = InstrumentationRegistry.getArguments().getString("shelfMeasureScroll") == "true"
        val metrics = if (measureScroll) ScrollMetrics() else null
        val scenario = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        try {
            scenario.onActivity { activity ->
                metrics?.attach(activity.window)
                activity.setContent {
                    TachiyomiPreviewTheme {
                        if (displayed == 1) {
                            val listState = rememberLazyListState()
                            val gridState = rememberLazyGridState()
                            val uiScope = rememberCoroutineScope()
                            val entries = remember {
                                flowOf(PagingData.from(mangas.map { MutableStateFlow(it) as StateFlow<Manga> }))
                            }.collectAsLazyPagingItems()
                            val indices = if (mode == 0) {
                                listState.layoutInfo.visibleItemsInfo.map { it.index }
                            } else {
                                gridState.layoutInfo.visibleItemsInfo.map { it.index }
                            }
                            val local = rememberConnectionShelfEntries(
                                source,
                                entries,
                                { indices },
                                if (mode == 0) 2 else 1,
                            )
                            SideEffect {
                                list = listState
                                grid = gridState
                                animationContext.set(uiScope.coroutineContext.minusKey(Job))
                                result.set(local)
                                visible.set(indices)
                            }
                            when (mode) {
                                0 -> BrowseSourceList(
                                    resolveManga = { resolveShelfManga(source, it, local[it.url]) },
                                    state = listState,
                                    mangaList = entries,
                                    contentPadding = PaddingValues(0.dp),
                                    showLibraryBadges = false,
                                    contentHeader = { Text("Fixture header") },
                                    downloadState = { local[it.url]?.downloads() },
                                    entryLabel = {
                                        rendered[it.id] = it
                                        it.title
                                    },
                                    entryBadge = { manga ->
                                        Box(
                                            Modifier.size(8.dp).onGloballyPositioned {
                                                positions[manga.id] = it.positionInWindow() + Offset(4f, 4f)
                                            },
                                        )
                                    },
                                    onMangaClick = { clicked.set(it) },
                                    onMangaLongClick = {},
                                )
                                1 -> BrowseSourceCompactGrid(
                                    resolveManga = { resolveShelfManga(source, it, local[it.url]) },
                                    state = gridState,
                                    mangaList = entries,
                                    columns = GridCells.Fixed(
                                        3,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    showLibraryBadges = false,
                                    contentHeader = { Text("Fixture header") },
                                    downloadState = { local[it.url]?.downloads() },
                                    entryLabel = {
                                        rendered[it.id] = it
                                        it.title
                                    },
                                    entryBadge = { manga ->
                                        Box(
                                            Modifier.size(8.dp).onGloballyPositioned {
                                                positions[manga.id] = it.positionInWindow() + Offset(4f, 4f)
                                            },
                                        )
                                    },
                                    onMangaClick = { clicked.set(it) },
                                    onMangaLongClick = {},
                                )
                                else -> BrowseSourceComfortableGrid(
                                    resolveManga = { resolveShelfManga(source, it, local[it.url]) },
                                    state = gridState,
                                    mangaList = entries,
                                    columns = GridCells.Fixed(
                                        3,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    showLibraryBadges = false,
                                    contentHeader = { Text("Fixture header") },
                                    downloadState = { local[it.url]?.downloads() },
                                    entryLabel = {
                                        rendered[it.id] = it
                                        it.title
                                    },
                                    entryBadge = { manga ->
                                        Box(
                                            Modifier.size(8.dp).onGloballyPositioned {
                                                positions[manga.id] = it.positionInWindow() + Offset(4f, 4f)
                                            },
                                        )
                                    },
                                    onMangaClick = { clicked.set(it) },
                                    onMangaLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
            fun evidence() = "visible=${visible.get()} observed=${result.get().keys}"
            waitForWindow("initial", ::evidence) { result.get().isNotEmpty() }
            for (layout in 0..2) {
                withContext(Dispatchers.Main) { mode = layout }
                for (offset in listOf(0, 150, 450, 50)) {
                    metrics?.begin()
                    withTimeout(15000) {
                        withContext(if (measureScroll) checkNotNull(animationContext.get()) else Dispatchers.Main) {
                            if (layout == 0) {
                                if (measureScroll) {
                                    checkNotNull(
                                        list,
                                    ).animateScrollToItem(offset)
                                } else {
                                    checkNotNull(list).scrollToItem(offset)
                                }
                            } else {
                                if (measureScroll) {
                                    checkNotNull(
                                        grid,
                                    ).animateScrollToItem(offset)
                                } else {
                                    checkNotNull(grid).scrollToItem(offset)
                                }
                            }
                        }
                    }
                    waitForWindow("layout=$layout offset=$offset", ::evidence) {
                        val window = shelfObservationWindow(visible.get(), if (layout == 0) 2 else 1, mangas.size)
                        val expected = window.map { mangas[it].url }.toSet()
                        offset in visible.get() &&
                            expected.isNotEmpty() && expected.size < 150 && result.get().keys == expected
                    }
                    assertTrue(result.get().size < mangas.size)
                    assertTrue(result.get().values.all { it.total == 1L && it.downloaded == 0L })
                    if (metrics != null) {
                        delay(100)
                        val measured = metrics.finish().put("layout", layout).put("target", offset)
                            .put("observed", result.get().size).put("loaded", mangas.size)
                        if (offset != 0) check(measured.getInt("frame_samples") > 0)
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            0,
                            Bundle().apply { putString("shelf_scroll", measured.toString()) },
                        )
                    }
                }
                val leading = if (layout == 0) 2 else 1
                val indices = visible.get().map { it - leading }.filter { it in mangas.indices }
                val target = mangas[indices[indices.size / 2]]
                val note = "Live local state $layout"
                Injekt.get<MangaRepository>().update(
                    MangaUpdate(target.id, favorite = true, notes = note, viewerFlags = 123, coverLastModified = 987),
                )
                waitForWindow("projection layout=$layout", ::evidence) {
                    rendered[target.id]?.let {
                        it.favorite && it.notes == note && it.viewerFlags == 123L && it.coverLastModified == 987L
                    } == true
                }
                val point = checkNotNull(positions[target.id])
                val origin = IntArray(2)
                scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
                val down = SystemClock.uptimeMillis()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        point.x + origin[0],
                        point.y + origin[1],
                        0,
                    )
                    try {
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(automation.injectInputEvent(event, true))
                    } finally {
                        event.recycle()
                    }
                }
                waitForWindow("click layout=$layout", ::evidence) {
                    clicked.get()?.let { it.id == target.id && it.notes == note && it.favorite } == true
                }
            }
        } finally {
            try {
                scenario.onActivity { metrics?.detach(it.window) }
                withContext(Dispatchers.Main) { displayed = 0 }
                scenario.close()
            } finally {
                metrics?.close()
            }
        }
    }

    private class ScrollMetrics : Window.OnFrameMetricsAvailableListener {
        private val thread = HandlerThread("owned-shelf-frames").apply { start() }
        private val values = mutableListOf<Long>()
        private var missed = 0
        private var started = 0L
        private var allocated = 0L
        private var cpu = 0L
        private var descriptors = 0
        fun attach(window: Window) = window.addOnFrameMetricsAvailableListener(this, Handler(thread.looper))
        fun detach(window: Window) = window.removeOnFrameMetricsAvailableListener(this)
        fun close() {
            thread.quitSafely()
            thread.join(10000)
            check(!thread.isAlive)
        }

        @Synchronized
        fun begin() {
            values.clear()
            missed = 0
            allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            cpu = Process.getElapsedCpuTime()
            descriptors = File("/proc/self/fd").list()?.size ?: error("Missing own descriptor count")
            started = System.nanoTime()
        }

        @Synchronized
        override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
            if (started == 0L || metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) < started) return
            check(values.size < 10000)
            values += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            missed += dropCountSinceLastInvocation
        }

        @Synchronized
        fun finish(): JSONObject {
            val sorted = values.sorted()
            val result = JSONObject().put("elapsed_ms", (System.nanoTime() - started) / 1e6)
                .put("allocated_bytes", Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated)
                .put("cpu_ms", Process.getElapsedCpuTime() - cpu)
                .put("fd_before", descriptors).put("fd_after", File("/proc/self/fd").list()?.size ?: -1)
                .put("frame_samples", sorted.size).put("dropped_reports", missed)
            if (sorted.isNotEmpty()) {
                result.put("frame_total_p50_ms", sorted[sorted.size / 2] / 1e6)
                    .put("frame_total_p95_ms", sorted[(sorted.lastIndex * 0.95).toInt()] / 1e6)
                    .put("frame_total_max_ms", sorted.last() / 1e6)
            }
            started = 0
            return result
        }
    }

    private suspend fun waitForWindow(label: String, evidence: () -> String, predicate: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!predicate()) delay(16) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$label: ${evidence()}", error)
        }
    }

    private fun fixture(
        count: Int,
        block: suspend (Long, List<Manga>, List<Chapter>) -> Unit,
    ): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        try {
            val entries = mangas.insertNetworkManga(
                (0 until count).map {
                    Manga.create().copy(source = id, url = "shelf-fixture/$it", title = "Fixture $it")
                },
            )
            val units = chapters.addAll(
                entries.map {
                    Chapter.create().copy(mangaId = it.id, url = "${it.url}/chapter", name = "Unit")
                },
            )
            block(id, entries, units)
        } finally {
            mangas.deleteMangaBySourceId(id)
            context.deleteSharedPreferences("source_$id")
        }
    }
}
