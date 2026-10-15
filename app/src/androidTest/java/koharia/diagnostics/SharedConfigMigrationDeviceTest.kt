package koharia.diagnostics

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.AndroidSharedConfigStorage
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedConfigMigration
import koharia.connection.ui.SharedConfigSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SharedConfigMigrationDeviceTest {
    @Test
    fun choiceSurvivesExitAndCommitsWithoutChangingAppPreferences() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val name = "shared-config-fixture-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val storage = AndroidSharedConfigStorage(preferences)
        val selected = AtomicBoolean(false)
        val defaultsCreated = AtomicInteger()
        val migration = SharedConfigMigration(
            storage,
            {
                defaultsCreated.incrementAndGet()
                mapOf("theme" to 1, "font" to "original")
            },
            { listOf(LibraryConnectionProfile(7, "lanraragi", "Migration fixture")) },
            Json,
        )
        try {
            assertTrue(
                storage.commit(
                    mapOf(
                        "connection_config_mode" to "Separate",
                        "connection_active_id" to 7L,
                        "connection_shared::theme" to 3,
                        "connection_7::font" to "chosen",
                        "font" to "old",
                    ),
                ),
            )
            assertFalse(migration.initialize())
            assertEquals(0, defaultsCreated.get())
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SharedConfigSelection(migration) { selected.set(true) } } }
                withTimeout(5_000) {
                    while (find(
                            instrumentation.uiAutomation.rootInActiveWindow,
                            context.stringResource(MR.strings.shared_config_choose_title),
                        ) == null
                    ) {
                        delay(50)
                    }
                }
            }
            assertTrue(migration.isPending())
            assertFalse(selected.get())
            assertEquals(3, preferences.getInt("connection_shared::theme", 1))
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SharedConfigSelection(migration) { selected.set(true) } } }
                withTimeout(5_000) {
                    var clicked = false
                    while (!clicked) {
                        var node = find(
                            instrumentation.uiAutomation.rootInActiveWindow,
                            context.stringResource(MR.strings.action_ok),
                        )
                        while (node != null && !node.isClickable) node = node.parent
                        clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        if (!clicked) delay(50)
                    }
                    while (!selected.get()) delay(50)
                }
            }
            assertFalse(migration.isPending())
            assertEquals(1, defaultsCreated.get())
            assertEquals("chosen", preferences.getString("connection_shared::font", null))
            assertFalse(preferences.contains("connection_shared::theme"))
            assertFalse(preferences.contains("font"))
            assertEquals("chosen", preferences.getString("connection_7::font", null))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }
}
