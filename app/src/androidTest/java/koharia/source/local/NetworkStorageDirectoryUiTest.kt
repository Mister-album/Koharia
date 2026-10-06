package koharia.source.local

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class NetworkStorageDirectoryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun networkModesBrowseRetryAndSelectFolders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val activity = instrumentation.startActivitySync(
            Intent(context, EInkMotionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as EInkMotionFixtureActivity
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            var attempts = 0
            var selected: String? = null
            var created: Pair<String, String>? = null
            instrumentation.runOnMainSync {
                activity.setContent {
                    TachiyomiPreviewTheme {
                        key(mode) {
                            NetworkStorageDirectoryDialog(
                                initialPath = "",
                                draft = NetworkStorageDraft(NetworkStorageConfiguration(mode = mode), "user", "secret"),
                                onDismiss = {},
                                onConfirm = { selected = it },
                                creationAllowed = { true },
                                createDirectory = { parent, name ->
                                    if (name == "exists") throw StorageFailure(StorageFailure.Reason.CONFLICT)
                                    if (name == "denied") throw StorageFailure(StorageFailure.Reason.PERMISSION)
                                    created = parent to name
                                },
                                loadDirectories = { path ->
                                    if (attempts++ == 0) throw StorageFailure(StorageFailure.Reason.AUTH)
                                    if (path.isEmpty()) listOf(StorageEntry("中文 空格", true)) else emptyList()
                                },
                            )
                        }
                    }
                }
            }
            compose.onNodeWithText(context.stringResource(MR.strings.storage_browse_auth_failed)).assertExists()
            compose.onNodeWithTag("storage-browser-select").assertIsNotEnabled()
            compose.onNodeWithText(context.stringResource(MR.strings.action_retry)).performClick()
            compose.onNodeWithText("中文 空格").performClick()
            compose.onNodeWithTag("storage-browser-path").assertTextContains("中文 空格")
            compose.onNodeWithText(context.stringResource(MR.strings.storage_no_subfolders)).assertExists()
            compose.onNodeWithText(context.stringResource(MR.strings.storage_parent_folder)).performClick()
            compose.onNodeWithTag("storage-browser-path").assertTextContains("/")
            compose.onNodeWithText("中文 空格").performClick()
            compose.onNodeWithTag("storage-browser-create").performClick()
            compose.onNodeWithTag("storage-new-folder-name").performTextInput("exists")
            compose.onNodeWithTag("storage-new-folder-confirm").performClick()
            compose.onNodeWithText(context.stringResource(MR.strings.storage_create_exists)).assertExists()
            compose.onNodeWithTag("storage-new-folder-name").performTextReplacement("新建目录")
            compose.onNodeWithTag("storage-new-folder-confirm").performClick()
            compose.runOnIdle { assertEquals("中文 空格" to "新建目录", created) }
            compose.onNodeWithTag("storage-browser-path").assertTextContains("中文 空格/新建目录")
            compose.onNodeWithTag("storage-browser-create").performClick()
            compose.onNodeWithTag("storage-new-folder-name").performTextInput("denied")
            compose.onNodeWithTag("storage-new-folder-confirm").performClick()
            compose.onNodeWithText(context.stringResource(MR.strings.storage_create_denied)).assertExists()
            compose.onNodeWithTag("storage-new-folder-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("storage-new-folder-cancel").performClick()
            compose.onNodeWithTag("storage-browser-create").assertIsNotEnabled()
            compose.onNodeWithTag("storage-browser-select").performClick()
            compose.runOnIdle { assertEquals("中文 空格/新建目录", selected) }
        }
    }

    @Test fun manualPathEntryRejectsUnsafePathsAndBrowsesTheTypedFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val activity = instrumentation.startActivitySync(
            Intent(context, EInkMotionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as EInkMotionFixtureActivity
        var selected: String? = null
        instrumentation.runOnMainSync {
            activity.setContent {
                TachiyomiPreviewTheme {
                    NetworkStorageDirectoryDialog(
                        initialPath = "",
                        draft = NetworkStorageDraft(
                            NetworkStorageConfiguration(
                                mode = LibraryStorageMode.WEBDAV,
                                address = "https://dav.invalid:5006",
                            ),
                            "user",
                            "secret",
                        ),
                        onDismiss = {},
                        onConfirm = { selected = it },
                        loadDirectories = { path ->
                            when (path) {
                                "" -> listOf(StorageEntry("dav", true))
                                "dav/library" -> emptyList()
                                else -> throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
                            }
                        },
                        creationAllowed = { true },
                        createDirectory = { _, _ -> },
                    )
                }
            }
        }
        val invalid = context.stringResource(MR.strings.storage_browse_path_invalid)
        val empty = context.stringResource(MR.strings.storage_no_subfolders)
        compose.onNodeWithTag("storage-browser-manual-path").performClick()
        compose.onNodeWithTag("storage-browser-path-input").performTextInput("../outside")
        compose.onNodeWithTag("storage-browser-path-apply").performClick()
        compose.onNodeWithText(invalid).assertExists()
        compose.onNodeWithTag("storage-browser-path-input").performTextReplacement("dav/library")
        compose.onNodeWithTag("storage-browser-path-apply").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("storage-browser-path").assertTextContains("dav/library")
        compose.onNodeWithTag("storage-browser-select").performClick()
        compose.runOnIdle { assertEquals("dav/library", selected) }
    }
}
