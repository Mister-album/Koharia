package koharia.reader.resampling

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.reader.settings.MoireReductionSettings
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MoireSettingsDeviceTest {
    @Test
    fun readerSettingsPersistSharedThresholdAndNotifyActiveViewer() {
        assertFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = Injekt.get<ReaderPreferences>()
        val oldEnabled = preferences.moireReduction.get()
        val enabledWasSet = preferences.moireReduction.isSet()
        val oldThreshold = preferences.moireReductionThreshold.get()
        val thresholdWasSet = preferences.moireReductionThreshold.isSet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val updates = AtomicInteger()
        try {
            preferences.moireReduction.set(false)
            preferences.moireReductionThreshold.delete()
            assertEquals(50, preferences.moireReductionThreshold.get())
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    WebtoonConfig(scope, preferences).imagePropertyChangedListener = { updates.incrementAndGet() }
                    activity.setContent { MaterialTheme { Column { MoireReductionSettings(preferences) } } }
                }
                val label = context.getString(MR.strings.reader_moire_reduction.resourceId)
                awaitText(label)
                SystemClock.sleep(200)
                updates.set(0)
                click(label)
                awaitText(context.getString(MR.strings.reader_moire_threshold_percent.resourceId, 50))
                assertTrue(preferences.moireReduction.get())
                awaitUpdates(updates)
                updates.set(0)
                click(context.getString(MR.strings.reader_moire_threshold_percent.resourceId, 75))
                awaitText(context.getString(MR.strings.reader_moire_threshold_summary.resourceId, 75))
                assertEquals(75, preferences.moireReductionThreshold.get())
                awaitUpdates(updates)
                click(label)
                assertTrue(!preferences.moireReduction.get())
                click(label)
                awaitText(context.getString(MR.strings.reader_moire_threshold_summary.resourceId, 75))
                assertEquals(75, preferences.moireReductionThreshold.get())
            }
        } finally {
            scope.cancel()
            if (enabledWasSet) preferences.moireReduction.set(oldEnabled) else preferences.moireReduction.delete()
            if (thresholdWasSet) {
                preferences.moireReductionThreshold.set(
                    oldThreshold,
                )
            } else {
                preferences.moireReductionThreshold.delete()
            }
        }
    }

    private fun awaitUpdates(updates: AtomicInteger) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (updates.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Active viewer did not refresh", updates.get() > 0)
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
