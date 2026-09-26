package koharia.connection.ui

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ConnectionSearchControlsDeviceTest {
    @Test
    fun selectingCurrentSortReversesItAndCapabilityChangeRemovesOldOptions() {
        assertFixture()
        var selected by mutableStateOf("relevance")
        var ascending by mutableStateOf(true)
        var supportsRelevance by mutableStateOf(true)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        ConnectionSearchResults(
                            options = buildList {
                                if (supportsRelevance) {
                                    add(ConnectionSearchSortOption("relevance", "Relevance", supportsDirection = false))
                                }
                                add(ConnectionSearchSortOption("created", "Added", defaultAscending = false))
                                add(ConnectionSearchSortOption("name", "Name"))
                            },
                            selected = selected,
                            ascending = ascending,
                            onSelect = { value, direction ->
                                selected = value
                                ascending = direction
                            },
                        )
                    }
                }
            }
            click("Relevance")
            awaitText("Added", popup = true)
            click("Relevance", popup = true)
            scenario.onActivity { assertEquals(true, ascending) }
            click("Relevance")
            click("Added", popup = true)
            click("Added ↓")
            awaitText("Name", popup = true)
            click("Added ↓", popup = true)
            awaitText("Added ↑")
            scenario.onActivity {
                assertEquals("created", selected)
                assertEquals(true, ascending)
                supportsRelevance = false
            }
            // The icon state description stays unchanged while the option-dependent menu state is recreated.
            SystemClock.sleep(250)
            click("Added ↑")
            awaitText("Name", popup = true)
            assertTrue(findText("Relevance", popup = true) == null)
            click("Name", popup = true)
            awaitText("Name ↑")
        }
    }

    @Test
    fun scopeReturnsStableValueRatherThanMenuPosition() {
        assertFixture()
        var selected by mutableStateOf(7)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Column {
                            ConnectionSearchScope(
                                choices = listOf(ConnectionSearchChoice(7, "All"), ConnectionSearchChoice(42, "Books")),
                                selected = selected,
                                onSelect = { selected = it },
                            )
                        }
                    }
                }
            }
            click("All")
            click("Books", popup = true)
            scenario.onActivity { assertEquals(42, selected) }
            awaitText("Books")
        }
    }

    // Inspect only this fixture process's Compose windows, including dropdown popups.
    private fun findText(text: String, popup: Boolean = false): SemanticsNode? {
        var found: SemanticsNode? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = roots().filter { root -> isPopup(root) == popup }
                .flatMap(::nodes)
                .firstOrNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                        node.config.getOrNull(SemanticsProperties.StateDescription) == text
                }
        }
        return found
    }

    private fun roots(): Sequence<SemanticsNode> = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::views).filterIsInstance<ViewRootForTest>().map { it.semanticsOwner.rootSemanticsNode }

    private fun isPopup(root: SemanticsNode) = nodes(root).any { it.config.contains(SemanticsProperties.IsPopup) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(nodes(it)) }
    }

    private fun awaitText(text: String, popup: Boolean = false): SemanticsNode {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            findText(text, popup)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Text not visible: $text")
    }

    private fun click(text: String, popup: Boolean = false) {
        var node: SemanticsNode? = awaitText(text, popup)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            while (node != null && node?.config?.getOrNull(SemanticsActions.OnClick) == null) node = node?.parent
            assertTrue(
                "Cannot click $text",
                node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true,
            )
        }
        if (popup) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var open = true
            while (open && SystemClock.uptimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { open = roots().any(::isPopup) }
                if (open) SystemClock.sleep(50)
            }
            assertTrue("Popup did not close", !open)
        }
    }

    private fun assertFixture() {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }
}
