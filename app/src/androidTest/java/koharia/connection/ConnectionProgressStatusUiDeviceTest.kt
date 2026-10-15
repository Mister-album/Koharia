package koharia.connection

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ui.ConnectionProgressSyncStatusSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger

class ConnectionProgressStatusUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun reviewShowsBothStatesAndOnlyExplicitConfirmationSubmits() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val comparisons = AtomicInteger()
        val submissions = AtomicInteger()
        val submitted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : ConnectionProgressSyncStatusAdapter, ConnectionReadStatusReviewAdapter {
            override fun observeProgressSyncStatus(chapterUrls: List<String>) =
                kotlinx.coroutines.flow.flowOf(mapOf("book" to setOf(ConnectionProgressSyncStatus.HELD)))

            override suspend fun reviewReadStatus(chapterUrl: String): ConnectionReadStatusReview {
                assertEquals("book", chapterUrl)
                comparisons.incrementAndGet()
                return ConnectionReadStatusReview(false, false, true, true) {
                    submissions.incrementAndGet()
                    submitted.await()
                }
            }
        }
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiPreviewTheme {
                        Column(Modifier.systemBarsPadding()) {
                            ConnectionProgressSyncStatusSection(adapter, "Komga", mapOf("book" to "Fixture"))
                        }
                    }
                }
            }
            click(context.stringResource(MR.strings.connection_sync_status_count, "Komga", 1))
            click(context.stringResource(MR.strings.connection_sync_review))
            awaitLabel(context.stringResource(MR.strings.connection_sync_review_local_unread))
            awaitLabel(context.stringResource(MR.strings.connection_sync_review_remote_started))
            assertEquals(1, comparisons.get())
            assertEquals(0, submissions.get())
            click(context.stringResource(MR.strings.action_cancel))
            assertEquals(0, submissions.get())
            click(context.stringResource(MR.strings.connection_sync_review))
            click(context.stringResource(MR.strings.connection_sync_review_reissue))
            await { submissions.get() == 1 }
            click(context.stringResource(MR.strings.connection_sync_review_reissue))
            assertEquals(1, submissions.get())
            submitted.complete(Unit)
            await { find(context.stringResource(MR.strings.connection_sync_review_remote_started)) == null }
            assertEquals(2, comparisons.get())
        }
    }

    @Test
    fun statusUpdatesWhileOpenAndObsoleteAdapterCannotChangeTheNewDisplay() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val first = Adapter(ConnectionProgressSyncStatus.PENDING)
        val second = Adapter(ConnectionProgressSyncStatus.DENIED)
        val selected = MutableStateFlow(first)
        val label = context.stringResource(MR.strings.connection_sync_status_count, "Komga", 1)
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val adapter by selected.collectAsState()
                    TachiyomiPreviewTheme {
                        Column(Modifier.systemBarsPadding()) {
                            ConnectionProgressSyncStatusSection(adapter, "Komga", mapOf("book" to ""))
                        }
                    }
                }
            }
            click(label)
            awaitLabel(context.stringResource(MR.strings.connection_sync_status_pending))
            awaitLabel(context.stringResource(MR.strings.unknown_title))
            first.values.value = mapOf("book" to setOf(ConnectionProgressSyncStatus.HELD))
            awaitLabel(context.stringResource(MR.strings.connection_sync_status_held))
            assertTrue(find(context.stringResource(MR.strings.connection_sync_status_pending)) == null)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            await { first.active.get() == 0 }
            first.values.value = mapOf("book" to setOf(ConnectionProgressSyncStatus.CONFLICT))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitLabel(context.stringResource(MR.strings.connection_sync_status_conflict))
            selected.value = second
            await { second.active.get() == 1 && first.active.get() == 0 }
            first.values.value = mapOf("book" to setOf(ConnectionProgressSyncStatus.CONFLICT))
            click(label)
            awaitLabel(context.stringResource(MR.strings.connection_sync_status_denied))
            assertTrue(find(context.stringResource(MR.strings.connection_sync_status_conflict)) == null)
            second.values.value = mapOf("book" to setOf(ConnectionProgressSyncStatus.LOCAL_ONLY))
            awaitLabel(context.stringResource(MR.strings.connection_sync_status_local_only))
            assertTrue(find(context.stringResource(MR.strings.connection_sync_status_pending)) == null)
            second.values.value = emptyMap()
            await {
                find(label) == null &&
                    find(context.stringResource(MR.strings.connection_sync_status_denied)) == null
            }
        }
        await { second.active.get() == 0 }
        assertEquals(1, first.maximum.get())
        assertEquals(1, second.maximum.get())
    }

    private class Adapter(status: ConnectionProgressSyncStatus) : ConnectionProgressSyncStatusAdapter {
        val values = MutableStateFlow(mapOf("book" to setOf(status)))
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        override fun observeProgressSyncStatus(chapterUrls: List<String>) = flow {
            assertEquals(listOf("book"), chapterUrls)
            val subscriptions = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, subscriptions) }
            try {
                emitAll(values)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun awaitLabel(label: String) = await { find(label) != null }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Synchronization display did not reach the expected state", condition())
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun click(label: String) {
        awaitLabel(label)
        instrumentation.waitForIdleSync()
        val bounds = Rect().also { checkNotNull(find(label)).getBoundsInScreen(it) }
        assertTrue(!bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                down,
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
    }
}
