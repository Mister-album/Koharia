package eu.kanade.presentation.components

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ui.ConnectionLibraryToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.motion.EInkDisplayPolicy
import tachiyomi.presentation.core.motion.ProvideEInkDisplayPolicy

@SdkSuppress(minSdkVersion = 29)
class AdaptiveAppBarDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun shelfResizesWithoutClippingTitleAndKeepsActionsInBothDirectionsAndPolicies() {
        assertFixture()
        var width by mutableStateOf(360.dp)
        var rtl by mutableStateOf(false)
        var eInk by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var importCount = 0
        var refreshCount = 0
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                            LocalDensity provides Density(LocalDensity.current.density, fontScale),
                        ) {
                            ProvideEInkDisplayPolicy(EInkDisplayPolicy(enabled = eInk)) {
                                Box(Modifier.width(width).testTag("toolbar")) {
                                    ConnectionLibraryToolbar(
                                        title = "Koharia",
                                        searchQuery = null,
                                        onSearchQueryChange = {},
                                        onSearch = {},
                                        onCloseSearch = {},
                                        displayMode = LibraryDisplayMode.List,
                                        onDisplayModeChange = {},
                                        connectionProfiles = emptyList(),
                                        activeConnectionId = 0,
                                        onConnectionSelect = {},
                                        onFilterClick = {},
                                        onRefresh = { refreshCount++ },
                                        navigateUp = null,
                                        additionalActions = listOf(
                                            AppBar.Action("Import fixture", Icons.Outlined.Add, onClick = {
                                                importCount++
                                            }),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            await { find("Koharia") != null }
            assertTitleFits("compact")
            var compactSpacing = 0f
            var iconWidth = 0f
            onMain {
                compactSpacing = actionSpacing()
                iconWidth = clickable(find("Import fixture")!!).boundsInRoot.width
            }
            click("Import fixture")
            scenario.onActivity { width = 480.dp }
            await { actionSpacing() > compactSpacing + 1f }
            onMain { assertEquals(iconWidth, clickable(find("Import fixture")!!).boundsInRoot.width, 0.01f) }
            assertTitleFits("wide")
            scenario.onActivity {
                width = 360.dp
                rtl = true
                eInk = true
            }
            await { actionSpacing() <= compactSpacing + 1f }
            assertTitleFits("RTL E-Ink compact")
            click("Import fixture")
            val context = instrumentation.targetContext
            click(context.stringResource(MR.strings.action_menu_overflow_description))
            click(context.stringResource(MR.strings.connection_refresh))
            scenario.onActivity {
                width = 480.dp
                fontScale = 1.3f
            }
            await { textLayout().layoutInput.density.fontScale == 1.3f }
            assertTitleFits("large font")
            scenario.onActivity {
                assertEquals(2, importCount)
                assertEquals(1, refreshCount)
            }
        }
    }

    @Test
    fun searchFieldAndTextActionStillWork() {
        assertFixture()
        var query by mutableStateOf<String?>("find")
        var submitted = ""
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.width(320.dp)) {
                            SearchToolbar(
                                searchQuery = query,
                                onChangeSearchQuery = { query = it },
                                actions = {
                                    TextButton(onClick = { submitted = query.orEmpty() }) { Text("Submit fixture") }
                                },
                            )
                        }
                    }
                }
            }
            await { allNodes().any { it.config.contains(SemanticsActions.SetText) } }
            onMain {
                val field = allNodes().first { it.config.contains(SemanticsActions.SetText) }
                assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("find book")))
            }
            await { query == "find book" }
            click("Submit fixture")
            scenario.onActivity { assertEquals("find book", submitted) }
        }
    }

    private fun assertTitleFits(stage: String) = onMain {
        val layout = textLayout()
        // Paragraph allocation can exceed the intrinsic Text node width without clipping glyphs.
        assertTrue(
            "$stage: the full title must fit its allocated width",
            layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.layoutInput.constraints.maxWidth,
        )
        assertFalse("$stage: title must not overflow vertically", layout.didOverflowHeight)
        assertFalse("$stage: title must not be ellipsized", layout.isLineEllipsized(0))
        val titleLeft = find("Koharia")!!.boundsInRoot.left
        val glyphLeft = titleLeft + layout.getLineLeft(0)
        val glyphRight = titleLeft + layout.getLineRight(0)
        val toolbarBounds = allNodes().first {
            it.config.getOrNull(SemanticsProperties.TestTag) == "toolbar"
        }.boundsInRoot
        assertTrue(
            "$stage: title must stay inside toolbar",
            glyphLeft >= toolbarBounds.left && glyphRight <= toolbarBounds.right,
        )
        allNodes().filter { it.config.contains(SemanticsActions.OnClick) }.forEach {
            assertTrue(
                "$stage: title must not overlap any action",
                glyphRight <= it.boundsInRoot.left || glyphLeft >= it.boundsInRoot.right,
            )
        }
    }

    private fun textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(find("Koharia")!!.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }

    private fun actionSpacing(): Float {
        val centers = allNodes().filter { it.config.contains(SemanticsActions.OnClick) }
            .map { it.boundsInRoot.center.x }.sorted().toList()
        assertEquals("All six shelf actions must remain", 6, centers.size)
        return (centers.last() - centers.first()) / (centers.size - 1)
    }

    private fun click(label: String) {
        await { find(label) != null }
        onMain {
            val node = clickable(find(label)!!)
            assertTrue("Action must be visible", node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }

    private fun clickable(node: SemanticsNode): SemanticsNode =
        if (node.config.contains(SemanticsActions.OnClick)) node else clickable(checkNotNull(node.parent))

    private fun find(label: String): SemanticsNode? = allNodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    // As in ConnectionSearchControlsDeviceTest, inspect only this fixture process's Compose roots.
    private fun allNodes(): Sequence<SemanticsNode> = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::views).filterIsInstance<ViewRootForTest>()
        .flatMap { nodes(it.semanticsOwner.rootSemanticsNode) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(nodes(it)) }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Toolbar did not reach expected state")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun assertFixture() = assertEquals("app.koharia.dev.einkfixture", instrumentation.targetContext.packageName)
}
