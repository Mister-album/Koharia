package koharia.connection

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.epub.EpubTransitionFixtureActivity
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.model.Manga

class ShelfPagingAnchorDeviceTest {
    @Test
    fun commonLayoutsRetainVisibleBookWhenThePagingWindowMoves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val rows: List<StateFlow<Manga>> = (0 until 2300).map { index ->
            MutableStateFlow(Manga.create().copy(source = 42, url = "fixture/$index", title = "Fixture $index"))
        }
        for (mode in 0..2) {
            val pages = MutableStateFlow(PagingData.from(rows.take(300)))
            val grid = LazyGridState()
            val list = LazyListState()
            lateinit var items: LazyPagingItems<StateFlow<Manga>>
            var presented = false
            FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        items = pages.collectAsLazyPagingItems()
                        presented = true
                        TachiyomiTheme {
                            when (mode) {
                                0 -> BrowseSourceList(
                                    state = list,
                                    mangaList = items,
                                    resolveManga = {
                                        it
                                    },
                                    contentPadding = PaddingValues(),
                                    showLibraryBadges = false,
                                    contentHeader = { Text("Fixture header") },
                                    onMangaClick = {},
                                    onMangaLongClick = {},
                                )
                                1 -> BrowseSourceComfortableGrid(
                                    state = grid, mangaList = items, resolveManga = {
                                        it
                                    }, columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(), showLibraryBadges = false,
                                    contentHeader = {
                                        Text("Fixture header")
                                    }, onMangaClick = {}, onMangaLongClick = {},
                                )
                                else -> BrowseSourceCompactGrid(
                                    state = grid, mangaList = items, resolveManga = {
                                        it
                                    }, columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(), showLibraryBadges = false,
                                    contentHeader = {
                                        Text("Fixture header")
                                    }, onMangaClick = {}, onMangaLongClick = {},
                                )
                            }
                        }
                    }
                }
                fun awaitWindow(first: Int) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity {
                            ready =
                                presented && items.peek(0)?.value?.url == "fixture/$first"
                        }
                        if (!ready) SystemClock.sleep(50)
                    }
                    assertTrue("Paging window was not presented", ready)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(100)
                }
                awaitWindow(0)
                runBlocking(Dispatchers.Main) {
                    if (mode == 0) list.scrollToItem(152) else grid.scrollToItem(151)
                }
                fun firstVisibleUrl(): String? {
                    var result: String? = null
                    scenario.onActivity {
                        val index = if (mode == 0) list.firstVisibleItemIndex - 2 else grid.firstVisibleItemIndex - 1
                        result = items.peek(index.coerceAtLeast(0))?.value?.url
                    }
                    return result
                }
                instrumentation.waitForIdleSync()
                for (start in listOf(25, 50, 75, 50, 25, 0)) {
                    val anchor = firstVisibleUrl()
                    pages.value = PagingData.from(rows.subList(start, start + 300))
                    awaitWindow(start)
                    val actual = firstVisibleUrl()
                    if (mode == 0) {
                        assertEquals("List anchor moved at window=$start", anchor, actual)
                    } else {
                        // Dropping an odd-sized page can move the anchor to the second column.
                        // It must still be in the first visible row, never a page away.
                        val distance = anchor!!.substringAfterLast('/').toInt() -
                            actual!!.substringAfterLast('/').toInt()
                        assertTrue("Grid anchor left the visible row: $anchor -> $actual", distance in 0..1)
                    }
                }
            }
        }
    }
}
