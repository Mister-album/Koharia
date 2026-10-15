package koharia.connection

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.ConnectionLibraryTab
import koharia.source.local.LocalBookshelf
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryIndex
import koharia.source.local.LocalLibraryOrganizationMode
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConnectionSourceVisibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun lateAndReplacedSourcesUpdateHomeAndSettings(): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        for (settings in listOf(false, true)) verifyScreen(settings)
    }

    private suspend fun verifyScreen(settings: Boolean): Unit = coroutineScope {
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val restorer = sources as ConnectionSourceRestorer
        val active = connections.activeConnectionId
        val columns = Injekt.get<LibraryPreferences>().chapterCoverGridColumns
        val onboarding = Injekt.get<BasePreferences>().shownOnboardingFlow
        val previousActive = active.get()
        val hadActive = active.isSet()
        val previousColumns = columns.get()
        val hadColumns = columns.isSet()
        val previousOnboarding = onboarding.get()
        val hadOnboarding = onboarding.isSet()
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("visibility_host", if (settings) "settings" else "home")
                putLong("fixture_previous_active", previousActive)
                putBoolean("fixture_had_active", hadActive)
                putInt("fixture_previous_columns", previousColumns)
                putBoolean("fixture_had_columns", hadColumns)
                putBoolean("fixture_previous_onboarding", previousOnboarding)
                putBoolean("fixture_had_onboarding", hadOnboarding)
            },
        )
        val profile = profiles.add("local-folder", "Source visibility ${UUID.randomUUID()}")
        val root = File(context.cacheDir, "source-visibility-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putLong("fixture_owned_profile", profile.id)
                putString("fixture_owned_name", profile.name)
                putString("fixture_owned_cache", root.name)
            },
        )
        val publish = CompletableDeferred<Unit>()
        var registration: Job? = null
        fun config(separated: Boolean): LocalLibraryConfig {
            val kinds = if (separated) {
                listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)
            } else {
                listOf(LocalLibraryContentType.BOOKS)
            }
            return LocalLibraryConfig(
                setupCompleted = true,
                enabledContentTypes = kinds.toSet(),
                roots = kinds.map { kind ->
                    LocalLibraryRootConfig(
                        id = kind.name,
                        treeUri = root.toURI().toString(),
                        contentType = kind,
                        bookshelfId = kind.name + if (separated) "-series" else "-single",
                    )
                },
                bookshelves = kinds.map { kind ->
                    LocalBookshelf(
                        kind.name + if (separated) "-series" else "-single",
                        "Fixture ${kind.name}",
                        kind,
                        if (separated) {
                            LocalLibraryOrganizationMode.SERIES
                        } else {
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES
                        },
                    )
                },
            )
        }
        suspend fun configure(separated: Boolean) {
            profiles.commitConfiguration(profile.id) { draft ->
                LocalLibraryPreferences(profile.id, Json, draft).apply {
                    setConfig(config(separated))
                    setIndex(LocalLibraryIndex(scannedAt = System.currentTimeMillis(), items = emptyList()))
                }
            }
        }
        try {
            onboarding.set(true)
            columns.set(4)
            configure(true)
            active.set(profile.id)
            val initial = checkNotNull(sources.get(profile.id) as? LocalFolderSource)
            val absent = CompletableDeferred<Unit>()
            registration = launch {
                restorer.withSourceConfiguration(profile.id) {
                    absent.complete(Unit)
                    publish.await()
                }
            }
            withTimeout(10_000) { absent.await() }
            assertEquals(null, sources.get(profile.id))
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(if (settings) SettingsLibraryScreen else HomeScreen)
                        }
                    }
                }
                suspend fun verify(phase: String, expanded: Boolean) {
                    val current = sources.get(profile.id) as? LocalFolderSource
                    assertEquals(
                        if (expanded) {
                            setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK)
                        } else {
                            setOf(LibraryContentScope.ALL)
                        },
                        current?.availableContentScopes() ?: setOf(LibraryContentScope.ALL),
                    )
                    try {
                        if (settings) awaitSettings(expanded) else awaitHome(expanded)
                    } catch (error: TimeoutCancellationException) {
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply {
                                putString("fixture_visible_labels", visibleLabels().joinToString("|"))
                                putString("fixture_navigation_labels", navigationLabels().joinToString("|"))
                            },
                        )
                        throw error
                    }
                    assertEquals(profile.id, active.get())
                    assertEquals(4, columns.get())
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("source_visibility", "${if (settings) "settings" else "home"};$phase;$expanded")
                        },
                    )
                }
                verify("absent", false)
                publish.complete(Unit)
                withTimeout(10_000) { registration.join() }
                val firstPublished = checkNotNull(sources.get(profile.id))
                assertNotSame(initial, firstPublished)
                verify("late-registration", true)
                configure(false)
                val secondPublished = checkNotNull(sources.get(profile.id))
                assertNotSame(firstPublished, secondPublished)
                verify("same-id-single-file", false)
                configure(true)
                assertNotSame(secondPublished, sources.get(profile.id))
                verify("same-id-series", true)
            }
        } finally {
            withContext(NonCancellable) {
                publish.complete(Unit)
                registration?.join()
                ConnectionLibraryTab.clearAllRuntimeState()
                profiles.remove(profile.id).getOrThrow()
                if (hadActive) active.set(previousActive) else active.delete()
                if (hadColumns) columns.set(previousColumns) else columns.delete()
                if (hadOnboarding) onboarding.set(previousOnboarding) else onboarding.delete()
                withTimeout(10_000) { while (sources.get(profile.id) != null) delay(25) }
                check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
                assertEquals(previousColumns, columns.get())
                assertEquals(previousActive, active.get())
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("visibility_cleanup", "owned-profile-root-removed;shared-state-restored")
                    },
                )
            }
        }
    }

    private suspend fun awaitHome(expanded: Boolean) = withTimeout(15_000) {
        val comics = context.stringResource(MR.strings.label_comics)
        val books = context.stringResource(MR.strings.label_books)
        val library = context.stringResource(MR.strings.label_library)
        val history = context.stringResource(MR.strings.label_recent_manga)
        val more = context.stringResource(MR.strings.label_more)
        val expected = if (expanded) setOf(comics, books, history, more) else setOf(library, history, more)
        while (true) {
            if (navigationLabels() == expected) break
            delay(100)
        }
    }

    private fun navigationLabels(): Set<String> {
        val history = context.stringResource(MR.strings.label_recent_manga)
        val more = context.stringResource(MR.strings.label_more)
        return nodes(freshRoot())
            .map { labels(it) }
            .filter { history in it && more in it }
            .minByOrNull { it.size }
            .orEmpty()
    }

    private suspend fun awaitSettings(expanded: Boolean) = withTimeout(30_000) {
        val series = context.stringResource(MR.strings.pref_series_chapter_display_group)
        val updates = context.stringResource(MR.strings.connection_content_updates)
        while (true) {
            val labels = allSettingsLabels()
            if (updates in labels && (series in labels) == expanded) break
            delay(100)
        }
    }

    private suspend fun allSettingsLabels(): Set<String> {
        delay(250)
        for (attempt in 0 until 20) {
            if (scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) break
            delay(180)
        }
        val labels = mutableSetOf<String>()
        var previous = emptySet<String>()
        var unchanged = 0
        for (attempt in 0 until 30) {
            val visible = visibleLabels()
            labels += visible
            unchanged = if (visible == previous) unchanged + 1 else 0
            if (unchanged == 3) return labels
            previous = visible
            if (scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return labels
            delay(180)
        }
        error("Fixture settings list did not reach its end")
    }

    private fun scroll(action: Int): Boolean? = nodes(freshRoot())
        .firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(action)

    private fun visibleLabels(): Set<String> = labels(freshRoot())

    private fun freshRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun labels(root: AccessibilityNodeInfo?): Set<String> = nodes(root)
        .filter { it.isVisibleToUser }
        .flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }
        .toSet()

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root == null) return@buildList
        add(root)
        for (index in 0 until root.childCount) addAll(nodes(root.getChild(index)))
    }
}
