package koharia.smanga

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.smanga.ui.SmangaShelfError
import koharia.smanga.ui.SmangaShelfException
import koharia.smanga.ui.smangaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SmangaShelfErrorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun localizedFailureUsesTheCenteredEmptyStateAndWorkingActions(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val error = SmangaShelfException(SmangaException(SmangaException.Reason.PERMISSION))
        val retries = AtomicInteger()
        val settings = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Box(Modifier.fillMaxSize()) {
                            SmangaShelfError(error, { retries.incrementAndGet() }, { settings.incrementAndGet() })
                        }
                    }
                }
            }
            val message = awaitLabel(context.smangaError(error))
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertNull(find(root, "SmangaShelfException", substring = true))
            assertNull(find(root, context.stringResource(MR.strings.smanga_cache_incomplete)))
            val area = Rect().also { root.getBoundsInScreen(it) }
            val bounds = Rect().also { message.getBoundsInScreen(it) }
            assertTrue(bounds.exactCenterY() in (area.top + area.height() * 0.3f)..(area.top + area.height() * 0.7f))
            assertTrue(abs(bounds.centerX() - area.centerX()) < 2)
            click(awaitLabel(context.stringResource(MR.strings.action_retry)))
            click(awaitLabel(context.stringResource(MR.strings.pref_connection_settings)))
            withTimeout(5_000) {
                while (retries.get() == 0 || settings.get() == 0) delay(25)
            }
            assertEquals(1, retries.get())
            assertEquals(1, settings.get())
        }
    }

    private suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(10_000) {
        while (true) {
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return@withTimeout it }
            delay(100)
        }
        error("Unreachable")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun find(node: AccessibilityNodeInfo?, label: String, substring: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()
        if (text == label || (substring && text?.contains(label) == true)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label, substring)?.let { return it }
        return null
    }
}
