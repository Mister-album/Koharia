package eu.kanade.presentation.more.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.komga.KomgaConnectionProvider
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.local.LocalFolderConnectionProvider
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.suwayomi.SuwayomiConnectionProvider
import koharia.testing.FixtureActivityLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.InfoScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnboardingGuidesTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val expectedProviderIds = listOf(
        KomgaConnectionProvider.ID,
        LanraragiConnectionProvider.ID,
        SuwayomiConnectionProvider.ID,
        KavitaConnectionProvider.ID,
        SmangaConnectionProvider.ID,
        LocalFolderConnectionProvider.ID,
    )

    @Test
    fun everyRegisteredProviderHasAGuideAndItsOwnSetupAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val providers = Injekt.get<ConnectionRegistry>().availableProviders()
        assertEquals(expectedProviderIds, providers.map { it.id })
        assertEquals(
            context.stringResource(MR.strings.connection_provider_local),
            providers.single { it.id == LocalFolderConnectionProvider.ID }.displayName,
        )
        val selectedProviders = CopyOnWriteArrayList<String>()
        val restores = AtomicInteger()
        val finishes = AtomicInteger()
        FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiPreviewTheme {
                        InfoScreen(
                            icon = Icons.Outlined.RocketLaunch,
                            headingText = context.stringResource(MR.strings.onboarding_heading),
                            subtitleText = context.stringResource(MR.strings.onboarding_description),
                            acceptText = context.stringResource(MR.strings.onboarding_action_finish),
                            onAcceptClick = { finishes.incrementAndGet() },
                        ) {
                            GuidesStep(
                                providers = providers,
                                onAddConnection = { selectedProviders.add(it) },
                                onRestoreBackup = { restores.incrementAndGet() },
                            ).Content()
                        }
                    }
                }
            }
            val buttonPositions = providers.map { provider ->
                compose.onNodeWithTag("onboarding-provider-${provider.id}").fetchSemanticsNode().positionInRoot.y
            }
            assertTrue(buttonPositions.zipWithNext().all { (first, second) -> first < second })
            providers.forEach { provider ->
                val profile = LibraryConnectionProfile(-System.nanoTime(), provider.id, "Onboarding fixture")
                assertNotNull(
                    provider.createSettingsScreen(profile, isNew = true, completeOnboardingOnSave = true),
                )
                compose.onNodeWithText(context.stringResource(provider.onboardingDescription)).assertDoesNotExist()
                compose.onNodeWithText(provider.displayName).assertExists()
                val button = compose.onNodeWithTag("onboarding-provider-${provider.id}")
                    .performScrollTo().assertIsDisplayed().assertIsEnabled()
                val finishBounds = compose.onNodeWithText(context.stringResource(MR.strings.onboarding_action_finish))
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(button.fetchSemanticsNode().boundsInRoot.bottom <= finishBounds.top)
                button.performClick()
                try {
                    compose.waitUntil(5_000) { selectedProviders.lastOrNull() == provider.id }
                } catch (error: Throwable) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        android.os.Bundle().apply {
                            putString("provider", provider.id)
                            putString("selected", selectedProviders.joinToString())
                            putString("button_bounds", button.fetchSemanticsNode().boundsInRoot.toString())
                            putString(
                                "finish_bounds",
                                compose.onNodeWithText(context.stringResource(MR.strings.onboarding_action_finish))
                                    .fetchSemanticsNode().boundsInRoot.toString(),
                            )
                            putInt("finish_clicks", finishes.get())
                        },
                    )
                    throw error
                }
            }
            assertEquals(providers.map { it.id }, selectedProviders.toList())
            compose.onNodeWithText(context.stringResource(MR.strings.getting_started_guide))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.stringResource(MR.strings.pref_restore_backup))
                .performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { restores.get() == 1 }
            assertEquals(0, finishes.get())
        }
    }

    @Test
    fun addLibraryDialogUsesTheSameProviderOrderAndNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val providers = Injekt.get<ConnectionRegistry>().availableProviders()
        assertEquals(expectedProviderIds, providers.map { it.id })
        FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiPreviewTheme {
                        Navigator(LibraryConnectionProfilesScreen(openAddDialog = true))
                    }
                }
            }
            val positions = providers.map { provider ->
                compose.onNode(hasText(provider.displayName) and hasAnyAncestor(isDialog()))
                    .assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
            }
            assertTrue(positions.zipWithNext().all { (first, second) -> first < second })
            compose.onNode(hasText(context.stringResource(MR.strings.action_cancel)) and hasAnyAncestor(isDialog()))
                .performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
    }
}
