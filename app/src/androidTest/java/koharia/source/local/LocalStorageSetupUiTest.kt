package koharia.source.local

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionProfileManager
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class LocalStorageSetupUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: EInkMotionFixtureActivity

    @Test fun allModesUseOrderedSetupSteps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        activity = instrumentation.startActivitySync(
            Intent(context, EInkMotionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as EInkMotionFixtureActivity
        LibraryStorageMode.entries.forEach(::exercise)
    }

    private fun exercise(mode: LibraryStorageMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add("local-folder", "Setup fixture")
        val address = if (mode ==
            LibraryStorageMode.SMB
        ) {
            "smb://127.0.0.1:18445/library/"
        } else {
            "http://127.0.0.1:18765/"
        }
        NetworkStoragePreferences(profile.id).save(NetworkStorageConfiguration(mode = mode, address = address), "", "")
        val comics = context.stringResource(MR.strings.local_library_default_comics_bookshelf)
        val books = context.stringResource(MR.strings.local_library_default_books_bookshelf)
        val initial = LocalLibraryConfig().withInitialBookshelves(comics, books)
        LocalLibraryPreferences(profile.id, Json).setConfig(
            initial.copy(
                managedBaseTreeUri = if (mode == LibraryStorageMode.LOCAL) context.cacheDir.toURI().toString() else "",
                enabledContentTypes = setOf(LocalLibraryContentType.COMICS),
            ),
        )
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.setContent {
                    TachiyomiPreviewTheme {
                        key(profile.id) {
                            Navigator(LocalFolderSettingsScreen(profile.id, profile.name, null, isNew = true))
                        }
                    }
                }
            }
            compose.onNodeWithTag("storage-mode-${mode.name}").assertIsSelected()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            if (mode != LibraryStorageMode.LOCAL) {
                compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
                compose.onNodeWithTag("local-setup-next").performClick()
            }
            val rootTitle = context.stringResource(MR.strings.storage_setup_default_folder)
            compose.waitUntil(10000) { compose.onAllNodesWithText(rootTitle).fetchSemanticsNodes().isNotEmpty() }
            if (mode != LibraryStorageMode.LOCAL) {
                compose.onNodeWithText(rootTitle).performClick()
                // The browser starts at the folder the seeded address already points at, so the
                // selected root is confirmed here instead of walking the share list again.
                compose.waitUntil(10000) {
                    compose.onAllNodes(
                        hasTestTag("storage-browser-select") and isEnabled(),
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("storage-browser-select").performClick()
            }
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").performClick()
            val guide = context.stringResource(MR.strings.local_library_organization_guide_title)
            compose.waitUntil(10000) { compose.onAllNodesWithText(guide).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(
                context.stringResource(MR.strings.local_library_mode_series),
            )[0].performScrollTo().performClick()
            val individualModes = compose.onAllNodesWithText(
                context.stringResource(MR.strings.local_library_mode_individual),
            )
            individualModes[individualModes.fetchSemanticsNodes().lastIndex].performScrollTo().performClick()
            compose.onNodeWithText(
                context.stringResource(MR.strings.local_library_setup_metadata_summary),
            ).assertDoesNotExist()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-next"))
            compose.onNodeWithTag("local-setup-next").assertIsEnabled().performClick()
            compose.onNodeWithTag("local-settings-list").performScrollToNode(
                hasText(context.stringResource(MR.strings.local_library_setup_metadata_summary)),
            )
            compose.onNodeWithText(
                context.stringResource(MR.strings.local_library_setup_metadata_summary),
            ).assertExists()
            repeat(if (mode == LibraryStorageMode.LOCAL) 3 else 4) {
                compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("local-setup-previous"))
                compose.onNodeWithTag("local-setup-previous").performClick()
            }
            compose.onNodeWithTag("local-settings-list").performScrollToNode(hasTestTag("storage-mode-${mode.name}"))
            compose.onNodeWithTag("storage-mode-${mode.name}").assertIsSelected()
            compose.onNodeWithTag("storage-primary-address").assertDoesNotExist()
        } catch (error: Throwable) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("setup_failure", "$mode: ${error.stackTraceToString()}")
                    putString("setup_tree", compose.onRoot().printToString())
                },
            )
            throw error
        } finally {
            runBlocking { manager.remove(profile.id).getOrThrow() }
        }
    }
}
