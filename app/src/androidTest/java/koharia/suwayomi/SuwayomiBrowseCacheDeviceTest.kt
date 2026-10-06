package koharia.suwayomi

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import coil3.imageLoader
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionProfileManager
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiConnectionProvider
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.ui.SuwayomiBrowseScreen
import koharia.suwayomi.ui.SuwayomiCategoriesScreen
import koharia.suwayomi.ui.SuwayomiDownloadsScreen
import koharia.suwayomi.ui.SuwayomiImageCache
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

class SuwayomiBrowseCacheDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun iconsReadTheExistingDiskCacheAndNeverReuseAnotherAccount(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val client = OkHttpClient()
        val json: Json = Injekt.get()
        val path = "/cache-fixture-${System.nanoTime()}.png"
        val disk = checkNotNull(context.imageLoader.diskCache)
        SuwayomiApi(
            client,
            json,
            "http://127.0.0.1:1",
            SuwayomiAuthMode.NONE,
            "",
            "",
            imageScope = "fixture/one",
        ).use { api ->
            val key = "suwayomi:${api.resourceCacheKey(path)}"
            try {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
                bitmap.recycle()
                val editor = checkNotNull(disk.openEditor(key))
                disk.fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
                val cached = checkNotNull(SuwayomiImageCache.load(context, api.client, api, path, 128))
                assertEquals(Color.MAGENTA, cached.getPixel(0, 0))
                SuwayomiApi(
                    client,
                    json,
                    "http://127.0.0.1:1",
                    SuwayomiAuthMode.NONE,
                    "",
                    "",
                    imageScope = "fixture/two",
                ).use { other ->
                    assertNull(SuwayomiImageCache.load(context, other.client, other, path, 128))
                }
            } finally {
                disk.remove(key)
            }
        }
    }

    @Test
    fun cachedBrowseMigrationCategoriesAndQueueSurviveReentryAndSessionReload(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager: ConnectionProfileManager = Injekt.get()
        val repository: SuwayomiRepository = Injekt.get()
        val profile = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi cache fixture")
        try {
            // A closed localhost port ensures these routes depend on cache rather than a live server.
            SuwayomiPreferences(profile.id).save("http://127.0.0.1:1", "", SuwayomiAuthMode.NONE, "", "")
            val source = withTimeout(15_000) {
                while (true) {
                    val candidate = Injekt.get<SourceManager>().get(profile.id) as? SuwayomiSource
                    if (candidate != null) return@withTimeout candidate.also { it.reload() }
                    delay(100)
                }
                error("Unreachable")
            }
            val session = source.session()
            val seed = SuwayomiCatalog(session.identity, repository, DirectoryFixture(), Injekt.get<Json>(), {})
            seed.sources()
            seed.extensions()
            seed.shelf()
            seed.sourceListing(12, 1, "", SuwayomiSourceMangaType.POPULAR, emptyList(), seed.listingRevision())

            var route by mutableStateOf("browse")
            var entry by mutableIntStateOf(0)
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme {
                            key(route, entry) {
                                when (route) {
                                    "browse" -> Navigator(SuwayomiBrowseScreen(profile.id))
                                    "categories" -> Navigator(SuwayomiCategoriesScreen(profile.id))
                                    "queue" -> Navigator(SuwayomiDownloadsScreen(profile.id))
                                    else -> Text("Fixture shelf")
                                }
                            }
                        }
                    }
                }
                waitFor("Fixture Source")
                compose.onAllNodesWithText(context.stringResource(MR.strings.suwayomi_no_sources)).fetchSemanticsNodes()
                    .let { assertTrue(it.isEmpty()) }
                compose.onNodeWithText(context.stringResource(MR.strings.suwayomi_browse_extensions)).performClick()
                waitFor("Fixture Extension")
                compose.onNodeWithText(context.stringResource(MR.strings.suwayomi_browse_migration)).performClick()
                waitFor("Fixture Source")

                repeat(2) {
                    compose.runOnIdle { route = "shelf" }
                    waitFor("Fixture shelf")
                    if (it == 1) source.reload()
                    compose.runOnIdle {
                        entry++
                        route = "browse"
                    }
                    waitFor("Fixture Source")
                }
                compose.onNodeWithText("Fixture Source").performClick()
                waitFor("Fixture Manga")
                compose.runOnIdle { route = "categories" }
                waitFor("Fixture Category")

                source.session().downloadSnapshot.value = SuwayomiDownloadStatus(
                    queue = listOf(
                        SuwayomiDownloadItem(
                            chapter = SuwayomiDownloadChapter(11, 3, name = "Fixture queued chapter"),
                            manga = SuwayomiManga(3, "Fixture Manga"),
                        ),
                    ),
                )
                compose.runOnIdle { route = "queue" }
                waitFor("Fixture queued chapter")
                compose.onNodeWithText(context.stringResource(MR.strings.action_resume)).assertIsNotEnabled()
            }
        } finally {
            manager.remove(profile.id).getOrThrow()
            repository.removeConnection(profile.id)
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private class DirectoryFixture : SuwayomiService {
        override suspend fun sources() = listOf(SuwayomiSourceInfo(12, "Fixture Source", "en"))
        override suspend fun extensions(
            refresh: Boolean,
        ) = listOf(SuwayomiExtension(name = "Fixture Extension", pkgName = "fixture", isInstalled = true))
        override suspend fun categories() = listOf(SuwayomiCategory(1, "Fixture Category"))
        override suspend fun libraryPage(
            after: String?,
        ) = SuwayomiNodes(listOf(SuwayomiManga(3, "Fixture Manga", sourceId = 12)))
        override suspend fun discoverSourceManga(
            sourceId: Long,
            page: Int,
            query: String?,
            type: SuwayomiSourceMangaType,
            filters: List<SuwayomiFilterChange>,
        ) =
            SuwayomiSourcePage(listOf(SuwayomiManga(3, "Fixture Manga", sourceId = 12)), false)
        override suspend fun manga(id: Int): SuwayomiManga = error("Unused")
        override suspend fun chapters(mangaId: Int): List<SuwayomiChapter> = error("Unused")
        override suspend fun chapter(id: Int): SuwayomiChapter = error("Unused")
        override suspend fun pages(chapterId: Int): SuwayomiPages = error("Unused")
        override suspend fun updateChapter(id: Int, pageIndex: Int?, read: Boolean?): SuwayomiChapter = error("Unused")
    }
}
