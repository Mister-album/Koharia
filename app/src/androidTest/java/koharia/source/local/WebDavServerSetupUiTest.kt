package koharia.source.local

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionProfileManager
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** Uses the loopback WebDAV fixture on reversed port 18765. */
class WebDavServerSetupUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun authenticatesWithServerAddressBeforeChoosingFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add("local-folder", "WebDAV setup fixture")
        val preferences = NetworkStoragePreferences(profile.id)
        preferences.save(NetworkStorageConfiguration(mode = LibraryStorageMode.WEBDAV), "", "")
        val endpoint = "http://127.0.0.1:18765/"
        val base = NetworkStorageConfiguration(mode = LibraryStorageMode.WEBDAV, address = endpoint)
        val folder = "webdav-setup-${UUID.randomUUID()}"
        NetworkStorageRuntime.backend(base, endpoint, "", "").use { backend ->
            runBlocking { backend.createDirectory(folder) }
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, EInkMotionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as EInkMotionFixtureActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    TachiyomiPreviewTheme {
                        Navigator(LocalFolderSettingsScreen(profile.id, profile.name, null, isNew = true))
                    }
                }
            }
            val choose = context.stringResource(MR.strings.storage_setup_default_folder)
            compose.onNodeWithTag("storage-primary-address").assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            // Step two asks for a server endpoint only: a bare host and port is rejected with a
            // reason instead of being silently unusable.
            compose.onNodeWithTag("storage-primary-address").performScrollTo().performTextInput("127.0.0.1:18765")
            compose.onNodeWithText(context.stringResource(MR.strings.storage_webdav_address_invalid)).assertExists()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").assertIsNotEnabled()
            compose.onNodeWithTag("storage-primary-address").performScrollTo()
                .performTextReplacement("http://127.0.0.1:18765")
            compose.onNodeWithText(choose).assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            // Authentication reaches the endpoint without any folder path being typed first.
            compose.waitUntil(30000) { compose.onAllNodesWithText(choose).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage-primary-address").assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasText(choose))
            compose.onNodeWithText(choose).performClick()
            // The browser starts at the server endpoint root, so the folder is reachable without
            // typing its path in step two.
            compose.waitUntil(30000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(folder).performClick()
            compose.waitUntil(30000) {
                compose.onAllNodes(hasTestTag("storage-browser-select") and androidx.compose.ui.test.isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("storage-browser-select").performClick()
            compose.onNodeWithTag("storage-browser-select").assertDoesNotExist()
            compose.onNodeWithText(folder).assertExists()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").assertIsEnabled()
            assertEquals("", preferences.configuration.address)
            assertEquals("", preferences.username)
        } finally {
            instrumentation.runOnMainSync { activity.setContent {} }
            NetworkStorageRuntime.backend(base, endpoint, "", "").use {
                runBlocking { it.delete(it.stat(folder)) }
            }
            runBlocking { manager.remove(profile.id).getOrThrow() }
        }
    }
}
