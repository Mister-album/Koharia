package koharia.source.local

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Uses the isolated authenticated SMB fixture on reversed port 18446. */
class SmbServerSetupUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun authenticatesBeforeChoosingDefaultFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add("local-folder", "SMB discovery fixture")
        val preferences = NetworkStoragePreferences(profile.id)
        preferences.save(NetworkStorageConfiguration(mode = LibraryStorageMode.SMB), "", "")
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
            compose.onNodeWithTag("storage-primary-address").assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            compose.onNodeWithTag("storage-internal-address").assertDoesNotExist()
            compose.onNodeWithTag("storage-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("storage-internal-address").performScrollTo().performTextInput("127.0.0.1:18446")
            compose.onNodeWithTag("storage-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("storage-internal-address").assertDoesNotExist()
            compose.onNodeWithTag("storage-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("storage-internal-address").assertTextContains("127.0.0.1:18446")
            compose.onNodeWithTag("storage-advanced").performScrollTo().performClick()
            compose.onNodeWithTag("storage-primary-address").performScrollTo().performTextInput("127.0.0.1:18446")
            compose.onNodeWithTag("storage-username").performScrollTo().performTextInput("fixture")
            compose.onNodeWithTag("storage-password").performScrollTo().performTextInput("fixture-password")
            compose.onNodeWithText(context.stringResource(MR.strings.storage_setup_default_folder)).assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            val choose = context.stringResource(MR.strings.storage_setup_default_folder)
            compose.waitUntil(30000) { compose.onAllNodesWithText(choose).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage-primary-address").assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasText(choose))
            compose.onNodeWithText(choose).performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("LIBRARY").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("LIBRARY").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("中文 空格").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("中文 空格").performClick()
            val empty = context.stringResource(MR.strings.storage_no_subfolders)
            compose.waitUntil(30000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage-browser-select").performClick()
            compose.onNodeWithText("LIBRARY/中文 空格").assertExists()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            val guide = context.stringResource(MR.strings.local_library_organization_guide_title)
            compose.waitUntil(30000) { compose.onAllNodesWithText(guide).fetchSemanticsNodes().isNotEmpty() }
            repeat(2) {
                compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-previous"))
                compose.onNodeWithTag("local-setup-previous").performClick()
            }
            compose.onNodeWithTag("storage-password").performScrollTo().performTextReplacement("incorrect")
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            val failed = context.stringResource(MR.strings.storage_operation_failed)
            compose.waitUntil(30000) { compose.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.stringResource(MR.strings.storage_setup_default_folder)).assertDoesNotExist()
            compose.onNodeWithTag("storage-password").performScrollTo().performTextReplacement("fixture-password")
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText(choose).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").assertIsNotEnabled()
            assertEquals("", preferences.configuration.address)
            assertEquals("", preferences.username)
        } finally {
            instrumentation.runOnMainSync { activity.setContent {} }
            runBlocking { manager.remove(profile.id).getOrThrow() }
        }
    }
}
