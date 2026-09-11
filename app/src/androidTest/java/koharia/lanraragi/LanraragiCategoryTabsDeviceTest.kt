package koharia.lanraragi

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.lanraragi.LanraragiEntry
import koharia.lanraragi.ui.LanraragiCategoryTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LanraragiCategoryTabsDeviceTest {
    @Test
    fun categoriesAreVisibleAndCanSwitchBackToAll() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selected = AtomicReference<String?>(null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var current by remember { mutableStateOf<String?>(null) }
                    MaterialTheme {
                        Box(Modifier.padding(top = 32.dp)) {
                            LanraragiCategoryTabs(
                                categories = listOf(
                                    LanraragiEntry("static", LanraragiEntry.Kind.CATEGORY, "Static category"),
                                    LanraragiEntry("dynamic", LanraragiEntry.Kind.CATEGORY, "Dynamic category"),
                                ),
                                selectedId = current,
                                onSelect = {
                                    current = it
                                    selected.set(it)
                                },
                            )
                        }
                    }
                }
            }
            suspend fun clickLabel(label: String) {
                withTimeout(10_000) {
                    while (true) {
                        val node = findClickableLabel(instrumentation.uiAutomation.rootInActiveWindow, label)
                        if (node != null) {
                            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            break
                        }
                        delay(100)
                    }
                }
            }
            clickLabel("Static category")
            withTimeout(5_000) { while (selected.get() != "static") delay(50) }
            clickLabel(instrumentation.targetContext.stringResource(MR.strings.all))
            withTimeout(5_000) { while (selected.get() != null) delay(50) }
        }
    }

    private fun findClickableLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable) return target
                target = target.parent
            }
        }
        for (index in 0 until node.childCount) {
            findClickableLabel(node.getChild(index), label)?.let { return it }
        }
        return null
    }
}
