package koharia.reader.resampling

import android.content.Context
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
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
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
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MoireSettingsDeviceTest {
    @Test
    fun readerAndMoreSettingsShareAlgorithmsWithoutThresholdControls() {
        assertFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "resampling-settings-ui-${System.nanoTime()}"
        val preferences = ReaderPreferences(
            AndroidPreferenceStore(context, context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val updates = AtomicInteger()
        try {
            preferences.moireReduction.set(false)
            preferences.resamplingKernel.set(ResamplingKernel.MITCHELL)
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    WebtoonConfig(scope, preferences).resamplingChangedListener = { updates.incrementAndGet() }
                    activity.setContent { MaterialTheme { Column { MoireReductionSettings(preferences) } } }
                }
                val label = context.getString(MR.strings.reader_resampling_enabled.resourceId)
                click(label)
                awaitText(context.getString(ResamplingKernel.MITCHELL.stringRes.resourceId))
                assertTrue(preferences.moireReduction.get())
                assertNoReaderDescriptionsOrThresholds()
                click(context.getString(ResamplingKernel.LANCZOS3.stringRes.resourceId))
                click(context.getString(ResamplingQuality.SPEED.stringRes.resourceId))
                assertEquals(ResamplingKernel.LANCZOS3, preferences.resamplingKernel.get())
                assertEquals(ResamplingQuality.SPEED, preferences.resamplingQuality.get())
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme { PreferenceScreen(SettingsReaderScreen.comicPreferences(preferences).take(4)) }
                    }
                }
                awaitText(context.getString(MR.strings.reader_resampling_summary.resourceId))
                awaitText(context.getString(MR.strings.reader_resampling_quality_summary.resourceId))
                awaitText(context.getString(MR.strings.reader_resampling_scale_summary.resourceId))
                awaitText(context.getString(ResamplingKernel.LANCZOS3.stringRes.resourceId))
                click(context.getString(MR.strings.reader_resampling_algorithm.resourceId))
                click(context.getString(ResamplingKernel.MITCHELL.stringRes.resourceId))
                awaitText(context.getString(ResamplingKernel.MITCHELL.stringRes.resourceId))
                awaitText(context.getString(MR.strings.reader_resampling_scale_summary.resourceId))
                assertEquals(ResamplingKernel.MITCHELL, preferences.resamplingKernel.get())
                scenario.onActivity { activity ->
                    activity.setContent { MaterialTheme { Column { MoireReductionSettings(preferences) } } }
                }
                awaitText(context.getString(ResamplingQuality.SPEED.stringRes.resourceId))
                awaitText(context.getString(ResamplingKernel.MITCHELL.stringRes.resourceId))
                assertNoReaderDescriptionsOrThresholds()
                awaitUpdates(updates)
                click(label)
                assertTrue(!preferences.moireReduction.get())
                click(label)
                awaitText(context.getString(ResamplingKernel.MITCHELL.stringRes.resourceId))
                assertEquals(ResamplingKernel.MITCHELL, preferences.resamplingKernel.get())
                assertEquals(ResamplingQuality.SPEED, preferences.resamplingQuality.get())
                assertNoReaderDescriptionsOrThresholds()
            }
        } finally {
            scope.cancel()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun assertNoReaderDescriptionsOrThresholds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (resource in listOf(
            MR.strings.reader_resampling_summary,
            MR.strings.reader_resampling_quality_summary,
            MR.strings.reader_resampling_scale_summary,
        )) {
            assertTrue(findText(context.getString(resource.resourceId)) == null)
        }
        for (percent in listOf(25, 33, 50, 75, 100)) assertTrue(findText("$percent%") == null)
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
