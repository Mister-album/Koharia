package eu.kanade.tachiyomi.ui.history

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HistoryRefreshUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val refreshLabel get() = context.stringResource(MR.strings.action_webview_refresh)

    @Test
    fun emptyHistorySupportsBothRefreshEntrypoints() {
        val refreshes = AtomicInteger()
        withHistory(HistoryScreenModel.State(list = emptyList()), { refreshes.incrementAndGet() }) {
            awaitLabel(context.stringResource(MR.strings.information_no_recent_manga))
            click(awaitLabel(refreshLabel))
            awaitCondition { refreshes.get() == 1 }

            pullToRefresh()
            awaitCondition { refreshes.get() == 2 }
        }
    }

    @Test
    fun shortHistoryListSupportsPullToRefreshWithoutLosingItsRows() {
        val refreshes = AtomicInteger()
        val history = HistoryWithRelations(
            id = 1,
            chapterId = 11,
            mangaId = 1,
            title = "History refresh fixture",
            chapterNumber = 1.0,
            readAt = Date(),
            readDuration = 0,
            coverData = MangaCover(1, 7, false, null, 0),
        )
        withHistory(
            HistoryScreenModel.State(
                list = listOf(HistoryUiModel.Header(LocalDate.now()), HistoryUiModel.Item(history)),
            ),
            { refreshes.incrementAndGet() },
        ) {
            awaitLabel(history.title)
            pullToRefresh()
            awaitCondition { refreshes.get() == 1 }
            awaitLabel(history.title)
        }
    }

    @Test
    fun refreshingDisablesTheButtonAndPullGesture() {
        val refreshes = AtomicInteger()
        withHistory(
            HistoryScreenModel.State(list = emptyList(), isRefreshing = true),
            { refreshes.incrementAndGet() },
        ) {
            assertFalse(actionTarget(awaitLabel(refreshLabel)).isEnabled)
            pullToRefresh()
            instrumentation.waitForIdleSync()
            assertEquals(0, refreshes.get())
        }
    }

    private fun withHistory(state: HistoryScreenModel.State, onRefresh: () -> Unit, check: () -> Unit) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiPreviewTheme {
                        HistoryScreen(
                            state = state,
                            snackbarHostState = remember { SnackbarHostState() },
                            onSearchQueryChange = {},
                            onRefresh = onRefresh,
                            onClickCover = {},
                            onClickResume = { _, _ -> },
                            onDialogChange = {},
                        )
                    }
                }
            }
            check()
        }
    }

    private fun pullToRefresh() {
        val bounds = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        val x = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * 0.5f
        val endY = bounds.top + bounds.height() * 0.98f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..20) {
            SystemClock.sleep(25)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 20)
        }
        send(MotionEvent.ACTION_UP, endY)
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(actionTarget(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun actionTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (target.isEnabled && !target.isClickable) target = target.parent ?: return target
        return target
    }

    private fun awaitLabel(label: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitCondition {
            match = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            match != null
        }
        return checkNotNull(match)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("History refresh UI did not reach the expected state", condition())
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
