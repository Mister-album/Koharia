package koharia.lanraragi

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionScopedPreferenceStoreFactory
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class LanraragiReviewRegressionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtureVersion = "v80_test_${System.nanoTime()}"

    @Test
    fun localEventPersistsWhileMetadataIsStillInFlight() = runBlocking(Dispatchers.IO) {
        val source = connection()
        val archive = source.repository.entries(source.id).first { it.title == "Fixture book 5" }
        val url = source.resourceUrl(archive)
        control("metadata_delay=3")
        try {
            val pull = async { source.pullPageProgress(url, JsonObject(emptyMap())) }
            delay(300)
            withTimeout(1000) { source.recordLocalPageProgress(url, 1, 3, System.currentTimeMillis()) }
            assertTrue("Metadata request must still be in flight", !pull.isCompleted)
            assertEquals(1, source.repository.readStates(source.id).single().pageIndex)
            pull.await()
            assertEquals(1, source.repository.readStates(source.id).single().pageIndex)
        } finally {
            control("metadata_delay=0")
        }
    }

    @Test
    fun firstDisplayedPageIsDurableBeforeRemoteComparisonCompletes() = runBlocking(Dispatchers.IO) {
        val source = connection()
        val archive = source.repository.entries(source.id).first { it.title == "Fixture book 5" }
        val manga = source.materialize(archive)
        val chapter = LanraragiEntryOpenManager().prepareChapter(source, manga)
        val base = Injekt.get<BasePreferences>()
        val wasIncognito = base.incognitoMode.get()
        base.incognitoMode.set(false)
        val readerPrefs = Injekt.get<ConnectionScopedPreferenceStoreFactory>().readerPreferences(source.id)
        readerPrefs.showNavigationOverlayNewUser.set(false)
        readerPrefs.showNavigationOverlayOnStart.set(false)
        control("metadata_delay=5")
        try {
            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id),
            ).use {
                withTimeout(30_000) { while (source.repository.readStates(source.id).none { it.pending }) delay(50) }
                val pending = source.repository.readStates(source.id).single()
                assertEquals(0, pending.pageIndex)
                assertTrue(pending.initialPage)
            }
            assertTrue(source.repository.readStates(source.id).single().pending)
        } finally {
            control("metadata_delay=0")
            base.incognitoMode.set(wasIncognito)
        }
    }

    @Test
    fun choosingLocalPositionAfterConflictIsPersistedAsAnExplicitChoice() = runBlocking(Dispatchers.IO) {
        val source = connection()
        val archive = source.repository.entries(source.id).first { it.title == "Fixture book 5" }
        val manga = source.materialize(archive)
        val chapter = LanraragiEntryOpenManager().prepareChapter(source, manga)
        source.api.pushProgress(archive.id, 2)
        val base = Injekt.get<BasePreferences>()
        val wasIncognito = base.incognitoMode.get()
        base.incognitoMode.set(false)
        val prefs = Injekt.get<ConnectionScopedPreferenceStoreFactory>().readerPreferences(source.id)
        prefs.showNavigationOverlayNewUser.set(false)
        prefs.showNavigationOverlayOnStart.set(false)
        try {
            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id),
            ).use { scenario ->
                withTimeout(30_000) {
                    var conflict = false
                    while (!conflict) {
                        scenario.onActivity { conflict = it.viewModel.state.value.remoteProgressConflict != null }
                        if (!conflict) delay(100)
                    }
                }
                scenario.onActivity { it.viewModel.keepLocalProgress() }
                withTimeout(10_000) { while (source.api.archive(archive.id).progress != 1) delay(100) }
                assertEquals(0, source.repository.readStates(source.id).single().pageIndex)
            }
        } finally {
            base.incognitoMode.set(wasIncognito)
        }
    }

    @Test
    fun restoringPreferencesReplacesAnAlreadyCreatedApiSession() = runBlocking(Dispatchers.IO) {
        val source = connection()
        val oldApi = source.api
        assertEquals("0.9.80", oldApi.serverInfo().version)
        sourcePreferences("source_${source.id}").edit().putString("address", "http://127.0.0.1:38709/v70/lrr/").commit()
        assertNotSame(oldApi, source.api)
        assertEquals("0.9.70", source.api.serverInfo().version)
        val restoredPreferences = sourcePreferences("source_${source.id}")
        val key = tachiyomi.core.common.preference.Preference.privateKey("api_key")
        restoredPreferences.edit().putString(key, "invalid-fixture-key").commit()
        val failure = runCatching { source.api.serverInfo() }.exceptionOrNull()
        assertEquals(LanraragiException.Reason.AUTH, (failure as? LanraragiException)?.reason)
        restoredPreferences.edit().putString(key, "fixture-key").commit()
        assertEquals("0.9.70", source.api.serverInfo().version)
    }

    @Test
    fun previewRetryDecodesImageAfterServerRecovers() = runBlocking(Dispatchers.IO) {
        val source = connection()
        val archive = source.repository.entries(source.id).first { it.title == "Fixture book 5" }
        val manga = source.materialize(archive)
        val pages = source.api.pages(archive.id)
        val cache = Injekt.get<ChapterCache>()
        // A test-only URL suffix prevents previous tests' cached images from masking this failure.
        val chapter = LanraragiEntryOpenManager().prepareChapter(source, manga)
        cache.putPageListToCache(
            chapter,
            koharia.connection.ConnectionPageList(
                pages.mapIndexed { i, url ->
                    eu.kanade.tachiyomi.source.model.Page(i, imageUrl = "$url&retry_fixture=${source.id}")
                },
            ),
        )
        val firstUrl = "${pages.first()}&retry_fixture=${source.id}"
        control("fail_images=true")
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(LanraragiArchivePreviewScreen(manga.id, source.id)) }
                    }
                }
                val retry = withTimeout(15_000) {
                    var node: AccessibilityNodeInfo?
                    do {
                        node =
                            find(
                                instrumentation.uiAutomation.rootInActiveWindow,
                                context.stringResource(MR.strings.action_retry),
                            )
                        if (node == null) delay(100)
                    } while (node == null)
                    node
                }
                assertTrue(!cache.isImageInCache(firstUrl))
                control("fail_images=false")
                var target: AccessibilityNodeInfo? = retry
                while (target != null && !target.isClickable) target = target.parent
                assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                withTimeout(15_000) { while (!cache.isImageInCache(firstUrl)) delay(100) }
            }
        } finally {
            control("fail_images=false")
        }
    }

    private suspend fun connection(): LanraragiSource {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        control("reset=true&metadata_delay=0&fail_images=false")
        val profile = Injekt.get<ConnectionProfileManager>().add(LanraragiConnectionProvider.ID, "Review regression")
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/$fixtureVersion/lrr/", "fixture-key")
        val source = withTimeout(10_000) {
            while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
            Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
        }
        source.refreshLibrary().getOrThrow()
        return source
    }

    private fun control(query: String) {
        val request = Request.Builder().url(
            "http://127.0.0.1:38709/_fixture/control?version=$fixtureVersion&$query",
        ).build()
        OkHttpClient().newCall(request).execute().use { assertTrue(it.isSuccessful) }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
