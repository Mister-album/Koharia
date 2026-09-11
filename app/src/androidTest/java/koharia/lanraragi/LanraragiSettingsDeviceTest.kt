package koharia.lanraragi

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionProfileManager
import koharia.lanraragi.ui.LanraragiLibraryScreenModel
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSettingsScreen
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LanraragiSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun savingChecksConnectionAndPersistsDefaultsWithoutChangingOtherConnections() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val other = manager.add(LanraragiConnectionProvider.ID, "Settings isolation sentinel")
        val profile = manager.add(LanraragiConnectionProvider.ID, "Settings draft")
        val prefs = LanraragiPreferences(profile.id)
        val navigation = AtomicReference<Navigator>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(listOf(SettingsLanding(), LanraragiSettingsScreen(profile.id, isNew = true))) {
                            navigation.set(it)
                            CurrentScreen()
                        }
                    }
                }
            }
            edit(context.stringResource(MR.strings.lanraragi_connection_name), "LANraragi settings fixture")
            edit(context.stringResource(MR.strings.lanraragi_address), "http://127.0.0.1:38709/v80/lrr/")
            edit(context.stringResource(MR.strings.lanraragi_api_key), "fixture-key")
            assertTrue(prefs.address.isEmpty())
            assertTrue(prefs.apiKey.isEmpty())
            assertTrue(
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == context.stringResource(MR.strings.lanraragi_test_connection) ||
                        it.text?.toString() == context.stringResource(MR.strings.lanraragi_cache_title)
                } == null,
            )
            capture("connection")
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            val registeredSource = withTimeout(15_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
                Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
            }
            registeredSource.refreshLibrary().getOrThrow()
            instrumentation.runOnMainSync { navigation.get().push(LanraragiSettingsScreen(profile.id)) }
            click(context.stringResource(MR.strings.lanraragi_default_category))
            click("Dynamic fixture")
            click(context.stringResource(MR.strings.lanraragi_group_tanks))
            click(context.stringResource(MR.strings.lanraragi_archive_open_mode))
            click(context.stringResource(MR.strings.lanraragi_open_preview))
            capture("library")
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            assertEquals("LANraragi settings fixture", manager.profiles().first { it.id == profile.id }.name)
            assertEquals("http://127.0.0.1:38709/v80/lrr/", prefs.address)
            assertTrue(prefs.apiKey == "fixture-key")
            assertEquals("SET_1234567891", prefs.defaultCategory)
            assertFalse(prefs.groupCollections)
            assertEquals(LanraragiArchiveOpenMode.PAGE_PREVIEW, prefs.archiveOpenMode)
            assertTrue(LanraragiPreferences(other.id).address.isEmpty())
            assertEquals("Settings isolation sentinel", manager.profiles().first { it.id == other.id }.name)
            val source = withTimeout(15_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
                Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
            }
            val model = LanraragiLibraryScreenModel(source, null)
            try {
                assertEquals("SET_1234567891", model.state.value.filter.category)
                assertFalse(model.state.value.filter.grouped)
            } finally {
                model.screenModelScope.cancel()
            }

            instrumentation.runOnMainSync { navigation.get().push(LanraragiSettingsScreen(profile.id)) }
            edit(context.stringResource(MR.strings.lanraragi_connection_name), "Unsaved name")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            click(context.stringResource(MR.strings.komga_action_discard))
            withTimeout(10_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            assertEquals("LANraragi settings fixture", manager.profiles().first { it.id == profile.id }.name)
            assertEquals("SET_1234567891", prefs.defaultCategory)
        }
    }

    @Test
    fun failedConnectionCanCancelOrSaveWithEmptyKey() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add(LanraragiConnectionProvider.ID, "Failure prompt draft")
        val prefs = LanraragiPreferences(profile.id)
        val navigation = AtomicReference<Navigator>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(listOf(SettingsLanding(), LanraragiSettingsScreen(profile.id, isNew = true))) {
                            navigation.set(it)
                            CurrentScreen()
                        }
                    }
                }
            }
            edit(context.stringResource(MR.strings.lanraragi_address), "http://127.0.0.1:38709/v80/lrr/")
            click(context.stringResource(MR.strings.action_save))
            awaitNode { it.text?.toString() == context.stringResource(MR.strings.lanraragi_connection_failed_title) }
            capture("failure")
            assertTrue(prefs.address.isEmpty())
            click(context.stringResource(MR.strings.action_cancel))
            assertTrue(navigation.get().lastItem is LanraragiSettingsScreen)
            assertTrue(prefs.address.isEmpty())
            click(context.stringResource(MR.strings.action_save))
            click(context.stringResource(MR.strings.lanraragi_save_anyway))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            assertEquals("http://127.0.0.1:38709/v80/lrr/", prefs.address)
            assertTrue(prefs.apiKey.isEmpty())
            assertTrue(manager.profiles().any { it.id == profile.id })
        }
    }

    @Test
    fun abandoningNewDraftRemovesOnlyThatDraft() = runBlocking(Dispatchers.IO) {
        val manager = Injekt.get<ConnectionProfileManager>()
        val before = manager.profiles()
        val draft = manager.add(LanraragiConnectionProvider.ID, "Discard only this draft")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(listOf(SettingsLanding(), LanraragiSettingsScreen(draft.id, isNew = true)))
                    }
                }
            }
            edit(context.stringResource(MR.strings.lanraragi_connection_name), "Unsaved draft name")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            click(context.stringResource(MR.strings.komga_action_discard))
            withTimeout(10_000) { while (manager.profiles().any { it.id == draft.id }) delay(100) }
            assertEquals(before, manager.profiles())
        }
    }

    private suspend fun edit(label: String, value: String) {
        click(label)
        val field = awaitNode { it.isEditable }
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        )
        click(context.stringResource(MR.strings.action_ok))
    }

    private suspend fun click(label: String) {
        withTimeout(15_000) {
            while (true) {
                var target: AccessibilityNodeInfo? = awaitNode {
                    it.text?.toString() == label || it.contentDescription?.toString() == label
                }
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                delay(100)
            }
        }
        delay(250)
    }

    private suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeout(15_000) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                find(root, predicate)?.let { return@withTimeout it }
                find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(150)
            }
            error("Unreachable")
        }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/lanraragi-settings-$name.png",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }

    private class SettingsLanding : Screen() {
        @Composable override fun Content() {
            Text("Settings test landing")
        }
    }
}
