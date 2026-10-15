package koharia.diagnostics

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.LibraryConnectionProfile
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.lanraragi.ui.LanraragiLibraryScreenModel
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ShelfCacheDeviceTest {
    @Test
    fun missingCatalogueBootstrapsAndExistingCacheSurvivesNewSourceAndFailure(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val id = Injekt.get<ConnectionPreferences>().allocateConnectionId()
        val profile = LibraryConnectionProfile(id, "lanraragi", "Shelf cache fixture")
        val preferences = LanraragiPreferences(id)
        preferences.save("http://127.0.0.1:38709/v80_test_shelf/lrr/", "fixture-key")
        val downloadedOnly = Injekt.get<eu.kanade.domain.base.BasePreferences>().downloadedOnly
        val previousDownloadedOnly = downloadedOnly.get()
        val hadDownloadedOnly = downloadedOnly.isSet()
        downloadedOnly.set(false)
        var source = LanraragiSource(context, profile)
        var model: LanraragiLibraryScreenModel? = null
        try {
            source.networkAvailable.value = true
            source.onRegistered()
            val bootstrapped = kotlinx.coroutines.withTimeoutOrNull(15000) {
                while (source.repository.lastSync(id) == 0L) delay(100)
                true
            }
            assertTrue(
                "valid=${source.hasValidConnection()} online=${source.networkAvailable.value} " +
                    "downloadedOnly=${downloadedOnly.get()} running=${source.status.value.running} " +
                    "error=${source.status.value.error}",
                bootstrapped == true,
            )
            val entries = source.repository.entries(id)
            assertTrue(entries.isNotEmpty())
            val oldTime = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val generation = source.repository.lastSync(id) + 1
            source.repository.stage(id, generation, entries)
            source.repository.publish(id, generation, oldTime)
            source.close()
            source = LanraragiSource(context, profile)
            source.onRegistered()
            model = LanraragiLibraryScreenModel(source, null)
            withTimeout(5000) { while (!model.state.value.catalogLoaded) delay(50) }
            assertEquals(entries.size, model.state.value.entries.size)
            delay(1800)
            assertEquals("Even week-old cache must not refresh at startup", oldTime, source.repository.lastSync(id))
            source.refreshLibrary().getOrThrow()
            val refreshed = source.repository.lastSync(id)
            assertTrue(refreshed > oldTime)
            preferences.save("http://127.0.0.1:1/lrr/", "fixture-key")
            assertTrue(source.refreshLibrary().isFailure)
            assertEquals(refreshed, source.repository.lastSync(id))
            assertEquals(entries.size, source.repository.entries(id).size)
            val emptyGeneration = refreshed + 1
            source.repository.begin(id, emptyGeneration)
            source.repository.publish(id, emptyGeneration, refreshed)
            source.close()
            source = LanraragiSource(context, profile)
            source.onRegistered()
            delay(1800)
            assertTrue(source.repository.entries(id).isEmpty())
            assertEquals(refreshed, source.repository.lastSync(id))
            assertEquals(null, source.status.value.error)
        } finally {
            model?.screenModelScope?.cancel()
            source.close()
            source.repository.remove(id)
            sourcePreferences("source_$id").edit().clear().commit()
            if (hadDownloadedOnly) downloadedOnly.set(previousDownloadedOnly) else downloadedOnly.delete()
        }
    }

    @Test
    fun warmShelfReentryResumeAndNetworkRecoveryKeepCatalogueGeneration(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloadedOnly = Injekt.get<eu.kanade.domain.base.BasePreferences>().downloadedOnly
        val originalSelected = selected.get()
        val hadSelected = selected.isSet()
        val originalDownloaded = downloadedOnly.get()
        val hadDownloaded = downloadedOnly.isSet()
        val profile = manager.add("lanraragi", "Shelf lifecycle fixture ${System.nanoTime()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloadedOnly.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                LanraragiPreferences(profile.id, draft).save(
                    "http://127.0.0.1:38709/v80_test_lifecycle/lrr/",
                    "fixture-key",
                    groupCollections = false,
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as LanraragiSource
            withTimeout(15_000) { while (source.repository.lastSync(profile.id) == 0L) delay(50) }
            val entries = source.repository.entries(profile.id)
            assertTrue(entries.isNotEmpty())
            withTimeout(5_000) { while (source.status.value.running) delay(25) }
            val oldTime = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            source.repository.stage(profile.id, source.repository.lastSync(profile.id) + 1, entries)
            source.repository.publish(profile.id, source.repository.lastSync(profile.id) + 1, oldTime)
            val before = lifecycleCheckpoint("cold-complete")
            // No configuration change: rebuild the production Source with the same stored owner/cache.
            manager.commitConfiguration(profile.id) { }
            val previous = source
            source = sources.get(profile.id) as LanraragiSource
            assertTrue(source !== previous)
            assertEquals(oldTime, source.repository.lastSync(profile.id))
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(LanraragiLibraryScreen(profile.id, null, false)) }
                    }
                }
                withTimeout(15_000) {
                    while (!hasTitle(instrumentation.uiAutomation.rootInActiveWindow, "Fixture book 1")) delay(50)
                }
                suspend fun verifyPhase(phase: String, initial: JSONObject): JSONObject {
                    delay(5_500)
                    val after = lifecycleCheckpoint(phase)
                    assertEquals("$phase catalogue requests", initial.getInt("catalogue"), after.getInt("catalogue"))
                    assertEquals("$phase mutations", initial.getInt("mutations"), after.getInt("mutations"))
                    assertEquals("$phase generation", oldTime, source.repository.lastSync(profile.id))
                    assertEquals(entries, source.repository.entries(profile.id))
                    assertTrue(hasTitle(instrumentation.uiAutomation.rootInActiveWindow, "Fixture book 1"))
                    val progressReads = after.getInt("archiveReads") - initial.getInt("archiveReads")
                    status("$phase;catalogue=0;progress=$progressReads")
                    return after
                }
                val warm = verifyPhase("warm-source-and-screen", before)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = verifyPhase("foreground-resume", warm)
                // Exercise this Source's public connectivity consumer without changing device networking.
                source.networkAvailable.value = false
                delay(250)
                source.networkAvailable.value = true
                source.refreshIfStale()
                val recovered = verifyPhase("source-network-recovery", resumed)
                assertTrue(
                    "Recovery must retain independent progress reads",
                    recovered.getInt("archiveReads") > resumed.getInt("archiveReads"),
                )
            }
            status("warm-generation-and-entries-retained")
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(originalSelected) else selected.delete()
                if (hadDownloaded) downloadedOnly.set(originalDownloaded) else downloadedOnly.delete()
                status("owned-profile-removed;preferences-restored")
            }
        }
    }

    @Test
    fun failedBootstrapRetriesIntoValidEmptyCacheAndLaterRefreshRecovers(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<eu.kanade.domain.base.BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add("lanraragi", "Empty retry fixture ${System.nanoTime()}")
        var model: LanraragiLibraryScreenModel? = null
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            lifecycleCheckpoint("cold-failure")
            manager.commitConfiguration(profile.id) { draft ->
                LanraragiPreferences(profile.id, draft).save(
                    "http://127.0.0.1:38709/v80_test_lifecycle/lrr/",
                    "fixture-key",
                )
            }
            var source = sources.get(profile.id) as LanraragiSource
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use {
                withTimeout(15_000) {
                    while (source.status.value.running || source.status.value.error == null) delay(50)
                }
                assertEquals(0L, source.repository.lastSync(profile.id))
                assertTrue(source.repository.entries(profile.id).isEmpty())
                status("first-failure-is-missing")
                lifecycleCheckpoint("retry-empty")
                // Let the original registration bootstrap loop retry; no manual refresh call.
                withTimeout(20_000) {
                    while (source.repository.lastSync(profile.id) == 0L || source.status.value.running) delay(50)
                }
                val emptyGeneration = source.repository.lastSync(profile.id)
                assertTrue(source.repository.entries(profile.id).isEmpty())
                assertEquals(null, source.status.value.error)
                status("automatic-retry-published-valid-empty")
                val emptyRequests = lifecycleCheckpoint("empty-published")
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as LanraragiSource
                val warm = LanraragiLibraryScreenModel(source, null)
                model = warm
                withTimeout(5_000) { while (!warm.state.value.catalogLoaded) delay(50) }
                delay(5_500)
                val afterWarm = lifecycleCheckpoint("empty-warm-source")
                assertEquals(emptyRequests.getInt("catalogue"), afterWarm.getInt("catalogue"))
                assertEquals(emptyGeneration, source.repository.lastSync(profile.id))
                assertTrue(warm.state.value.entries.isEmpty())
                assertEquals(null, source.status.value.error)
                status("empty-cache-is-loaded-without-rebootstrap")
                lifecycleCheckpoint("refresh-failure")
                assertTrue(source.refreshLibrary().isFailure)
                assertEquals(emptyGeneration, source.repository.lastSync(profile.id))
                assertTrue(source.repository.entries(profile.id).isEmpty())
                status("failed-refresh-keeps-successful-empty-generation")
                lifecycleCheckpoint("refresh-success")
                source.refreshLibrary().getOrThrow()
                withTimeout(5_000) { while (warm.state.value.entries.isEmpty()) delay(25) }
                assertTrue(source.repository.lastSync(profile.id) > emptyGeneration)
                assertEquals(null, source.status.value.error)
                assertTrue(lifecycleCheckpoint("full-published").getInt("failedResponses") > 0)
                status("later-success-published-nonempty")
            }
        } finally {
            try {
                model?.screenModelScope?.cancel()
                manager.remove(profile.id).getOrThrow()
                Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    @Test
    fun officialServiceBootstrapRetainsSuccessfulEmptyShelf(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val bridge = checkNotNull(InstrumentationRegistry.getArguments().getString("lanraragiShelfBridge"))
        val bridgeUrl = URL(bridge)
        require(bridgeUrl.protocol == "http" && bridgeUrl.host == "127.0.0.1" && bridgeUrl.port == 20362)
        fun control(suffix: String = ""): JSONObject {
            val connection = URL(bridge + suffix).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<eu.kanade.domain.base.BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add("lanraragi", "Official empty shelf ${System.nanoTime()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            val address = control().getString("server")
            require(address == "http://127.0.0.1:20361/")
            manager.commitConfiguration(profile.id) { draft ->
                LanraragiPreferences(profile.id, draft).save(address, "")
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as LanraragiSource
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                withTimeout(15_000) {
                    while (source.status.value.running || source.status.value.error == null) delay(50)
                }
                assertEquals(0L, source.repository.lastSync(profile.id))
                assertTrue(source.repository.entries(profile.id).isEmpty())
                assertTrue(control("/state").getInt("failures") > 0)
                status("official-initial-failure-remains-missing")
                control("/allow")
                withTimeout(20_000) {
                    while (source.repository.lastSync(profile.id) == 0L || source.status.value.running) delay(50)
                }
                assertEquals(null, source.status.value.error)
                val savedEntries = source.repository.entries(profile.id)
                assertEquals(1, savedEntries.size)
                val defaultCategory = savedEntries.single()
                assertEquals(koharia.domain.lanraragi.LanraragiEntry.Kind.CATEGORY, defaultCategory.kind)
                assertEquals("\uD83D\uDD16 Favorites", defaultCategory.title)
                assertTrue(defaultCategory.members.isEmpty())
                val generation = source.repository.lastSync(profile.id)
                assertTrue(control("/state").getInt("emptyResponses") > 0)
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(LanraragiLibraryScreen(profile.id, null, false)) }
                    }
                }
                val emptyLabel = context.stringResource(tachiyomi.i18n.MR.strings.no_results_found)
                suspend fun awaitEmpty() = withTimeout(10_000) {
                    while (!hasTitle(instrumentation.uiAutomation.rootInActiveWindow, emptyLabel)) delay(50)
                }
                awaitEmpty()
                val before = control("/state").getInt("catalogue")
                status("official-automatic-retry-empty;ui-loaded")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as LanraragiSource
                assertTrue(source !== previous)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(LanraragiLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitEmpty()
                delay(5_500)
                val after = control("/state")
                assertEquals(before, after.getInt("catalogue"))
                assertEquals(generation, source.repository.lastSync(profile.id))
                assertEquals(savedEntries, source.repository.entries(profile.id))
                assertEquals(null, source.status.value.error)
                assertEquals(0, after.getInt("mutations"))
                assertEquals(0, after.getInt("unknown"))
                status("official-new-source-activity-empty;directory=0;generation-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    private fun lifecycleCheckpoint(phase: String): JSONObject {
        val connection = URL(
            "http://127.0.0.1:38709/_shelf/checkpoint?phase=$phase",
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasTitle(node: AccessibilityNodeInfo?, title: String): Boolean {
        if (node == null) return false
        node.refresh()
        if (node.isVisibleToUser &&
            (node.text?.toString() == title || node.contentDescription?.toString() == title)
        ) {
            return true
        }
        return (0 until node.childCount).any { hasTitle(node.getChild(it), title) }
    }
}
