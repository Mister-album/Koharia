package eu.kanade.presentation.manga

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@RunWith(AndroidJUnit4::class)
class ChapterSettingsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun fileSizeCanBeToggledWithDownloadTerminology() {
        verifyFileSizeToggle(isConnectionCacheMode = false)
    }

    @Test
    fun fileSizeCanBeToggledWithCacheTerminology() {
        verifyFileSizeToggle(isConnectionCacheMode = true)
    }

    private fun verifyFileSizeToggle(isConnectionCacheMode: Boolean) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val fileSize = MutableStateFlow(false)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val showFileSize by fileSize.collectAsState()
                    TachiyomiPreviewTheme {
                        ChapterSettingsDialog(
                            onDismissRequest = {},
                            isConnectionCacheMode = isConnectionCacheMode,
                            onDownloadFilterChanged = {},
                            onUnreadFilterChanged = {},
                            onBookmarkedFilterChanged = {},
                            scanlatorFilterActive = false,
                            onScanlatorFilterClicked = {},
                            onSortModeChanged = {},
                            onDisplayModeChanged = {},
                            showChapterReadProgress = false,
                            onShowChapterReadProgressChanged = {},
                            showChapterFileSize = showFileSize,
                            onShowChapterFileSizeChanged = { fileSize.value = it },
                            hideMissingChapters = false,
                            onHideMissingChaptersChanged = {},
                            onSetAsDefault = {},
                            onResetToDefault = {},
                        )
                    }
                }
            }
            val displayLabel = context.stringResource(MR.strings.action_display)
            val fileSizeLabel = context.stringResource(MR.strings.pref_show_chapter_file_size)
            clickLabel(displayLabel)
            clickLabel(fileSizeLabel)
            awaitCondition { fileSize.value }
            clickLabel(context.stringResource(MR.strings.action_sort))
            clickLabel(displayLabel)
            clickLabel(fileSizeLabel)
            awaitCondition { !fileSize.value }
        }
    }

    private fun clickLabel(label: String) {
        instrumentation.waitForIdleSync()
        var match: AccessibilityNodeInfo? = null
        awaitCondition {
            match = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            match != null
        }
        val bounds = Rect().also { checkNotNull(match).getBoundsInScreen(it) }
        assertTrue("No visible bounds for $label", !bounds.isEmpty)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        SystemClock.sleep(350)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Chapter display settings did not reach the expected state", condition())
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            return node
        }
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
