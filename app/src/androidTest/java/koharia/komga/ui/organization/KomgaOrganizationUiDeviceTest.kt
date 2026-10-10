package koharia.komga.ui.organization

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.MangaCover
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class KomgaOrganizationUiDeviceTest {
    @Test
    fun collectionAndReadListCardsKeepNamesCountsAndSelectionAcrossDisplayModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val items =
            listOf(
                KomgaOrganization(
                    "a",
                    "收藏夹示例",
                    seriesIds = listOf("s1", "s2"),
                    bookIds = listOf("b1", "b2"),
                ),
                KomgaOrganization("b", "阅读列表示例", seriesIds = listOf("s3"), bookIds = listOf("b3")),
            )
        var mode by mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.List)
        var kind by mutableStateOf(KomgaOrganizationKind.COLLECTION)
        var selected by mutableStateOf(emptySet<String>())
        var clicked: String? = null
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        OrganizationCards(
                            items,
                            kind,
                            0,
                            mode,
                            GridCells.Fixed(2),
                            PaddingValues(),
                            selected,
                            onClick = { clicked = it.id },
                            onLongClick = { selected = selected + it.id },
                            header = {},
                            cover = { MangaCover(-1, 0, false, null, 0) },
                        )
                    }
                }
            }
            click("收藏夹示例")
            scenario.onActivity { assertEquals("a", clicked) }
            click("阅读列表示例", long = true)
            scenario.onActivity {
                assertEquals(setOf("b"), selected)
                mode = LibraryDisplayMode.ComfortableGrid
                kind = KomgaOrganizationKind.READ_LIST
            }
            awaitText("收藏夹示例")
            awaitText("阅读列表示例")
            awaitText("2")
            val deadline = SystemClock.uptimeMillis() + 5_000
            var gridReady = false
            while (!gridReady && SystemClock.uptimeMillis() < deadline) {
                val first = awaitText("收藏夹示例")
                val second = awaitText("阅读列表示例")
                instrumentation.runOnMainSync {
                    gridReady = kotlin.math.abs(first.boundsInRoot.top - second.boundsInRoot.top) < 1f
                }
                if (!gridReady) SystemClock.sleep(50)
            }
            assertTrue("Expected both cards on the same grid row", gridReady)
            SystemClock.sleep(100)
            val directory =
                File(context.getExternalFilesDir(null), "komga-organization-ui").apply { mkdirs() }
            File(directory, "readlists-grid.png").outputStream().use {
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(nodes(it)) }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }

    private fun awaitText(text: String): SemanticsNode {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var result: SemanticsNode? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result =
                    WindowInspector.getGlobalWindowViews()
                        .asSequence()
                        .flatMap(::views)
                        .filterIsInstance<ViewRootForTest>()
                        .flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }
                        .firstOrNull { node ->
                            node.config.getOrNull(SemanticsProperties.Text)?.any {
                                it.text == text
                            } == true
                        }
            }
            result?.let {
                return it
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Text not visible: $text")
    }

    private fun click(text: String, long: Boolean = false) {
        var node: SemanticsNode? = awaitText(text)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val action = if (long) SemanticsActions.OnLongClick else SemanticsActions.OnClick
            while (node != null && node?.config?.getOrNull(action) == null) node = node?.parent
            assertTrue(node?.config?.getOrNull(action)?.action?.invoke() == true)
        }
    }
}
