package koharia.connection

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ui.ConnectionReconnectionNotice
import koharia.connection.ui.connectionSaveLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class ConnectionReconnectionUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun verifiedActivationIsExplicitAndReturnsToOrdinarySaveAfterActivation() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "activation-ui-${System.nanoTime()}"
        val storage = context.getSharedPreferences(name, 0)
        check(storage.all.isEmpty())
        val changes = ConnectionIdentityChanges(AndroidSharedConfigStorage(storage), Json)
        val selected = MutableStateFlow(false)
        changes.prepareImport(listOf(LibraryConnectionProfile(43, "kavita", "Fixture")))
        changes.readyImported(setOf(43))
        changes.published(43)
        val label = context.stringResource(MR.strings.connection_activate_verified)
        try {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val activate by selected.collectAsState()
                        TachiyomiPreviewTheme {
                            Column(Modifier.systemBarsPadding()) {
                                koharia.connection.ui.ConnectionReactivationPreference(
                                    43,
                                    activate,
                                    true,
                                    { selected.value = it },
                                    changes,
                                )
                                Text("action:" + connectionSaveLabel(43, changes, activate))
                            }
                        }
                    }
                }
                await { find("action:" + context.stringResource(MR.strings.connection_reconnect_as_new)) != null }
                var node = find(label)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue(checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { selected.value && find("action:$label") != null }
                changes.activateVerified(43)
                await { find("action:" + context.stringResource(MR.strings.action_save)) != null }
                assertTrue(find(context.stringResource(MR.strings.connection_activate_verified_help)) == null)
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun identityPauseExplainsNewConnectionWhileConfigurationPauseDoesNot() {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "reconnection-ui-${System.nanoTime()}"
        val storage = context.getSharedPreferences(name, 0)
        check(storage.all.isEmpty())
        val changes = ConnectionIdentityChanges(AndroidSharedConfigStorage(storage), Json)
        val source = MutableStateFlow(41L)
        val save = context.stringResource(MR.strings.action_save)
        val reconnect = context.stringResource(MR.strings.connection_reconnect_as_new)
        val explanation = context.stringResource(MR.strings.connection_reconnect_as_new_help)
        try {
            ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val id by source.collectAsState()
                        TachiyomiPreviewTheme {
                            Column(Modifier.systemBarsPadding()) {
                                ConnectionReconnectionNotice(id, changes)
                                Text(connectionSaveLabel(id, changes))
                            }
                        }
                    }
                }
                await { find(save) != null }
                changes.beginConfiguration(41)
                instrumentation.waitForIdleSync()
                assertTrue(find(save) != null && find(explanation) == null)
                // The aggregate paused set stays {41}; the UI must still observe the new reason.
                changes.prepareImport(listOf(LibraryConnectionProfile(41, "komga", "Fixture")))
                changes.readyImported(setOf(41))
                await { find(reconnect) != null && find(explanation) != null }
                source.value = 42
                await { find(save) != null && find(explanation) == null }
                scenario.moveToState(Lifecycle.State.CREATED)
                changes.prepareImport(listOf(LibraryConnectionProfile(42, "local-folder", "Fixture")))
                changes.readyImported(setOf(42))
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { find(reconnect) != null && find(explanation) != null }
                changes.finishConfiguration(41)
                assertTrue(changes.isPaused(41))
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        assertTrue("Reconnection presentation did not update", condition())
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
}
