package koharia.lanraragi

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import koharia.connection.ConnectionAddressVerification
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionValidation
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.lanraragi.ui.LanraragiLibraryScreen
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LanraragiSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "Settings UI ${System.nanoTime()}"
    private var previousActive = 0L

    @Before fun requireFixture() {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        previousActive = Injekt.get<ConnectionPreferences>().activeConnectionId.get()
    }

    @After fun removeOnlyOwnedProfiles(): Unit = runBlocking(Dispatchers.IO) {
        val manager = Injekt.get<ConnectionProfileManager>()
        Injekt.get<ConnectionPreferences>().activeConnectionId.set(previousActive)
        val owned = manager.profiles().filter { it.name.startsWith(prefix) }
        owned.asReversed().forEach { manager.remove(it.id).getOrThrow() }
        withTimeout(10_000) {
            while (owned.any { Injekt.get<SourceManager>().get(it.id) != null }) delay(50)
        }
    }

    @Test fun rootShelfRebindsAfterSettingsReplaceSameIdSource() = verifyShelfRebinding(root = true)

    @Test fun nestedShelfRebindsAfterSettingsReplaceSameIdSource() = verifyShelfRebinding(root = false)

    private fun verifyShelfRebinding(root: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val profile = manager.add(LanraragiConnectionProvider.ID, "$prefix shelf")
        manager.commitConfiguration(profile.id) { draft ->
            LanraragiPreferences(profile.id, draft).save(
                "http://127.0.0.1:38709/v80/lrr/",
                "fixture-key",
                defaultCategory = "SET_1234567890",
                groupCollections = false,
            )
        }
        val previous = sources.get(profile.id) as LanraragiSource
        previous.refreshLibrary().getOrThrow()
        Injekt.get<ConnectionPreferences>().activeConnectionId.set(profile.id)
        val navigation = AtomicReference<Navigator>()
        val shelf = if (root) RootShelf() else LanraragiLibraryScreen(profile.id, null, true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(shelf) {
                            navigation.set(it)
                            CurrentScreen()
                        }
                    }
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("shelf_phase", "$root:initial") })
            awaitNode {
                it.text?.toString() == "Fixture book 2" || it.contentDescription?.toString() == "Fixture book 2"
            }
            instrumentation.runOnMainSync { navigation.get().push(LanraragiSettingsScreen(profile.id)) }
            click(context.stringResource(MR.strings.lanraragi_default_category))
            click("Dynamic fixture")
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !== shelf) delay(50) }
            assertTrue(sources.get(profile.id) !== previous)
            assertEquals("SET_1234567891", LanraragiPreferences(profile.id).defaultCategory)
            instrumentation.sendStatus(0, Bundle().apply { putString("shelf_phase", "$root:source-replaced") })
            awaitNode {
                it.text?.toString() == "Fixture book 3" || it.contentDescription?.toString() == "Fixture book 3"
            }
        }
    }

    @Test
    fun savingChecksConnectionAndPersistsDefaultsWithoutChangingOtherConnections() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val other = manager.add(LanraragiConnectionProvider.ID, "$prefix sentinel")
        val profile = manager.add(LanraragiConnectionProvider.ID, "$prefix draft")
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
            edit(context.stringResource(MR.strings.lanraragi_connection_name), "$prefix saved")
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
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            val registeredSource = withTimeout(15_000) {
                while ((Injekt.get<SourceManager>().get(profile.id) as? LanraragiSource)?.connectionProfile !=
                    manager.profiles().first { it.id == profile.id }
                ) {
                    delay(100)
                }
                Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
            }
            registeredSource.refreshLibrary().getOrThrow()
            instrumentation.runOnMainSync { navigation.get().push(LanraragiSettingsScreen(profile.id)) }
            click(context.stringResource(MR.strings.lanraragi_default_category))
            click("Dynamic fixture")
            click(context.stringResource(MR.strings.lanraragi_group_tanks))
            click(context.stringResource(MR.strings.lanraragi_archive_open_mode))
            click(context.stringResource(MR.strings.lanraragi_open_preview))
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            assertEquals("$prefix saved", manager.profiles().first { it.id == profile.id }.name)
            assertEquals("http://127.0.0.1:38709/v80/lrr/", prefs.address)
            assertTrue(prefs.apiKey == "fixture-key")
            assertEquals("SET_1234567891", prefs.defaultCategory)
            assertFalse(prefs.groupCollections)
            assertEquals(LanraragiArchiveOpenMode.PAGE_PREVIEW, prefs.archiveOpenMode)
            assertTrue(LanraragiPreferences(other.id).address.isEmpty())
            assertEquals(other, manager.profiles().first { it.id == other.id })
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
            assertEquals("$prefix saved", manager.profiles().first { it.id == profile.id }.name)
            assertEquals("SET_1234567891", prefs.defaultCategory)

            val changes = Injekt.get<ConnectionIdentityChanges>()
            val repository = Injekt.get<LanraragiRepository>()
            val oldProfile = manager.profiles().first { it.id == profile.id }
            changes.prepareImport(listOf(oldProfile))
            changes.readyImported(setOf(profile.id))
            changes.published(profile.id)
            val archive = "0000000000000000000000000000000000000001"
            repository.record(profile.id, LanraragiReadState(archive, 1, 3, 2000, pending = true))
            val pending = repository.readState(profile.id, archive)
            val oldEntries = repository.entries(profile.id)
            val oldDirectory = source.downloadDirectoryName()
            Injekt.get<ConnectionPreferences>().activeConnectionId.set(profile.id)
            val before = manager.profiles()
            instrumentation.runOnMainSync { navigation.get().push(LanraragiSettingsScreen(profile.id)) }
            edit(context.stringResource(MR.strings.lanraragi_address), "http://127.0.0.1:38709/v70/lrr/")
            edit(context.stringResource(MR.strings.lanraragi_api_key), "invalid-fixture-key")
            click(context.stringResource(MR.strings.connection_reconnect_as_new))
            awaitNode { it.text?.toString() == authenticationFailure() }
            assertEquals(before, manager.profiles())
            assertEquals("http://127.0.0.1:38709/v80/lrr/", prefs.address)
            assertEquals(pending, repository.readState(profile.id, archive))
            click(context.stringResource(MR.strings.action_ok))
            edit(context.stringResource(MR.strings.lanraragi_api_key), "fixture-key")
            click(context.stringResource(MR.strings.connection_reconnect_as_new))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            val replacement = manager.profiles().single { candidate -> before.none { it.id == candidate.id } }
            assertTrue(replacement.name.startsWith(prefix))
            assertNotEquals(profile.id, replacement.id)
            assertEquals(replacement.id, Injekt.get<ConnectionPreferences>().activeConnectionId.get())
            assertTrue(manager.requiresIdentityReplacement(profile.id))
            assertFalse(manager.requiresIdentityReplacement(replacement.id))
            assertEquals(oldProfile, manager.profiles().first { it.id == profile.id })
            assertEquals(pending, repository.readState(profile.id, archive))
            assertEquals(oldEntries, repository.entries(profile.id))
            assertEquals(oldDirectory, source.downloadDirectoryName())
            assertTrue(repository.pendingReadStates(replacement.id).isEmpty())
            val replacedPreferences = LanraragiPreferences(replacement.id)
            assertEquals("http://127.0.0.1:38709/v70/lrr/", replacedPreferences.address)
            assertEquals("", replacedPreferences.defaultCategory)
            assertEquals("SET_1234567891", prefs.defaultCategory)
            val replacementSource = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(replacement.id) !is LanraragiSource) delay(50)
                Injekt.get<SourceManager>().get(replacement.id) as LanraragiSource
            }
            replacementSource.refreshLibrary().getOrThrow()
            assertTrue(repository.entries(replacement.id).any { it.id == archive })
            assertEquals(pending, repository.readState(profile.id, archive))
            assertTrue(repository.pendingReadStates(replacement.id).isEmpty())
            assertEquals(other, manager.profiles().first { it.id == other.id })
        }
    }

    @Test
    fun failedConnectionPreservesDraftUntilSuccessfulValidation() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add(LanraragiConnectionProvider.ID, "$prefix failure")
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
            awaitNode { it.text?.toString() == authenticationFailure() }
            assertTrue(prefs.address.isEmpty())
            click(context.stringResource(MR.strings.action_ok))
            assertTrue(navigation.get().lastItem is LanraragiSettingsScreen)
            assertTrue(prefs.address.isEmpty())
            edit(context.stringResource(MR.strings.lanraragi_api_key), "fixture-key")
            click(context.stringResource(MR.strings.action_save))
            withTimeout(15_000) { while (navigation.get().lastItem !is SettingsLanding) delay(100) }
            assertEquals("http://127.0.0.1:38709/v80/lrr/", prefs.address)
            assertEquals("fixture-key", prefs.apiKey)
            assertTrue(manager.profiles().any { it.id == profile.id })
        }
    }

    @Test
    fun abandoningNewDraftRemovesOnlyThatDraft() = runBlocking(Dispatchers.IO) {
        val manager = Injekt.get<ConnectionProfileManager>()
        val before = manager.profiles()
        val draft = manager.add(LanraragiConnectionProvider.ID, "$prefix discarded")
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

    private fun authenticationFailure() = ConnectionAddressVerification.Failure(
        ConnectionAddressVerification.Reason.AUTHENTICATION,
        endpoint = ConnectionValidation.Endpoint.PUBLIC,
    ).userMessage(context)

    private class SettingsLanding : Screen() {
        @Composable override fun Content() {
            Text("Settings test landing")
        }
    }

    private class RootShelf : Screen() {
        @Composable override fun Content() {
            val navigator = LocalNavigator.currentOrThrow
            TabNavigator(LibraryTab) {
                CompositionLocalProvider(LocalNavigator provides navigator) { LibraryTab.Content() }
            }
        }
    }
}
