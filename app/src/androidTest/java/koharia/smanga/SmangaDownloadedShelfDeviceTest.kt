package koharia.smanga

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaRepository
import koharia.smanga.ui.SmangaLibraryScreen
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SmangaDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun downloadedShelfUiChangesMediaSortAndRespondsToDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val detailsRoute = InstrumentationRegistry.getArguments().getString("standardDetailsRoute") == "true"
        val producerRoute = detailsRoute ||
            InstrumentationRegistry.getArguments().getString("shelfCountProducer") == "true"
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val repository = Injekt.get<SmangaRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val old = preference.get()
            restores += { if (present) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val previousActive = connections.activeConnectionId.get()
        val hadActive = connections.activeConnectionId.isSet()
        val profile = profiles.add("smanga", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val files = mutableMapOf<Long, UniFile>()
        var source: SmangaSource? = null
        val coverBytes = if (producerRoute) {
            ZipInputStream(archiveBytes().inputStream()).use { zip ->
                checkNotNull(zip.nextEntry)
                zip.readBytes()
            }
        } else {
            null
        }
        val server = UnexpectedRequestServer(coverBytes)
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, !producerRoute)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            if (detailsRoute) {
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, false)
                replace(Injekt.get<LibraryPreferences>().chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
            }
            connections.activeConnectionId.set(profile.id)
            SmangaPreferences(profile.id).save(server.address, "fixture", "fixture-password", 128)
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is SmangaSource) delay(25)
                (Injekt.get<SourceManager>().get(profile.id) as SmangaSource).also { it.reload() }
            }
            source = active
            check(provider.findSourceDirs(active).isEmpty())
            val session = active.session()
            server.coverPaths = (1L..3L).map { id ->
                session.api.coverUrl(id).toHttpUrl().let { url ->
                    url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()
                }
            }.toSet()
            if (detailsRoute) {
                server.loginPath = session.api.base.newBuilder().addPathSegment("login").build().encodedPath
            }
            val titles = listOf("UI download A", "UI download B", "UI download C")
            repository.putCache(
                profile.id,
                session.accountKey,
                "media",
                SmangaCacheEntry(
                    "data",
                    Json.encodeToString(
                        if (producerRoute) {
                            listOf(
                                SmangaMedia(10, "Media A"),
                            )
                        } else {
                            listOf(SmangaMedia(10, "Media A"), SmangaMedia(20, "Media B"))
                        },
                    ),
                    1,
                ),
            )
            if (producerRoute) {
                val entries = titles.mapIndexed { index, title ->
                    SmangaManga(index + 1L, 10, title, chapterCount = 3)
                }
                val key = "shelf/10/" + Json.encodeToString(listOf("", active.preferences.initialOrder()))
                    .encodeUtf8().sha256().hex()
                repository.putCache(
                    profile.id,
                    session.accountKey,
                    key,
                    SmangaCacheEntry("1", Json.encodeToString(SmangaMangaPage(entries, 1, 100, entries.size)), 1),
                )
            }
            val seed = archiveBytes()
            val detailUnits = (1L..3L).map { id -> SmangaChapter(id, 1, 10, "Detail unit $id", id.toFloat()) }
            if (detailsRoute) {
                repository.putCache(
                    profile.id,
                    session.accountKey,
                    "chapters/1",
                    SmangaCacheEntry("data", Json.encodeToString(detailUnits), 1),
                )
            }
            for ((index, title) in titles.withIndex()) {
                val remote = SmangaManga(
                    index + 1L,
                    if (index ==
                        1
                    ) {
                        20
                    } else {
                        10
                    },
                    title,
                    updatedAt = 100L + index,
                    chapterCount = 3,
                )
                repository.putCache(
                    profile.id,
                    session.accountKey,
                    "manga/${remote.id}",
                    SmangaCacheEntry("data", Json.encodeToString(remote), 1),
                )
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = session.mangaUrl(remote.id),
                            title = title,
                            favorite = true,
                            memo = JsonObject(mapOf("booksCount" to JsonPrimitive(2))),
                        ),
                    ),
                ).single()
                stored += manga
                check(provider.findMangaDirs(title, active).isEmpty())
                val directory = provider.getMangaDir(title, active).getOrThrow()
                check(directory.parentFile?.name == active.downloadDirectoryName())
                val file = checkNotNull(directory.createFile("fixture.cbz"))
                file.openOutputStream().use { it.write(seed) }
                files[manga.id] = file
                cache.addChapter("fixture.cbz", directory, manga)
                assertEquals(1, downloads.getDownloadCount(manga))
            }
            repository.putCache(
                profile.id,
                "foreign-account",
                "manga/1",
                SmangaCacheEntry("data", Json.encodeToString(SmangaManga(1, 20, "Foreign")), 2),
            )
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(SmangaLibraryScreen(profile.id, null, false)) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                awaitTitles(titles, titles)
                withTimeout(10_000) {
                    while (stored.filter { it.title in titles }.any {
                            mangas.getMangaById(it.id).memo["booksCount"] != JsonPrimitive(3)
                        }
                    ) {
                        delay(25)
                    }
                }
                for (original in stored.filter { it.title in titles }) {
                    val refreshed = mangas.getMangaById(original.id)
                    assertEquals(true, refreshed.favorite)
                    assertEquals(original.title, refreshed.title)
                    assertEquals(1, downloads.getDownloadCount(refreshed))
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("shelf_count_persistence", "old=2;cache=3;sql=3;manual=1;favorites=retained")
                    },
                )
                if (detailsRoute) {
                    val expected = stored.first()
                    check(Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id).isEmpty())
                    click(awaitLabel(titles[0]))
                    withTimeout(15_000) {
                        while (navigator?.lastItem !is MangaScreen) delay(50)
                    }
                    check((navigator!!.lastItem as MangaScreen).mangaId == expected.id)
                    withTimeout(15_000) {
                        while (!mangas.getMangaById(expected.id).initialized ||
                            Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id).size != 3
                        ) {
                            delay(50)
                        }
                    }
                    val details = mangas.getMangaById(expected.id)
                    check(details.source == profile.id && details.url == expected.url)
                    check(details.author.isNullOrBlank() && details.artist.isNullOrBlank())
                    check(details.description.isNullOrBlank())
                    val units = Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id)
                    check(units.map { it.url }.toSet() == detailUnits.map(session::chapterUrl).toSet())
                    check(units.all { it.mangaId == expected.id })
                    awaitLabel(context.stringResource(MR.strings.unknown_author))
                    awaitLabel(context.stringResource(MR.strings.description_placeholder), scroll = true)
                    awaitLabel(context.pluralStringResource(MR.plurals.manga_num_chapters, 3, 3), scroll = true)
                    for (unit in detailUnits) awaitLabel(unit.name, scroll = true)
                    scenario.onActivity { check(navigator!!.pop()) }
                    awaitTitles(titles, titles)
                    check(mangas.getMangaBySourceId(profile.id).size == stored.size)
                    for (manga in stored) {
                        check(files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed))
                    }
                    assertEquals(0, server.unexpectedQueries.get())
                    assertEquals(1, server.loginQueries.get())
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "standard_details",
                                "ordinary-shelf;canonical-owner;empty-metadata;three-units;returned",
                            )
                            putInt("detail_cover_reads", server.coverQueries.get())
                            putInt("detail_other_requests", server.unexpectedQueries.get())
                            putInt("detail_login_requests", server.loginQueries.get())
                        },
                    )
                    return@use
                }
                if (producerRoute) {
                    awaitLabel(context.stringResource(MR.strings.shelf_download_partial, 1L, 3L))
                    assertEquals(0, server.unexpectedQueries.get())
                    assertEquals(server.requests.get(), server.coverQueries.get())
                    for (manga in stored.filter { it.title in titles }) {
                        assertTrue(
                            files.getValue(manga.id).openInputStream().use {
                                it.readBytes()
                            }.contentEquals(seed),
                        )
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "ordinary_shelf_count_producer",
                                "actual-screen;old=2;cache=3;sql=3;" +
                                    "manual=1;bytes=retained",
                            )
                            putInt("producer_requests", server.requests.get())
                            putInt("producer_cover_requests", server.coverQueries.get())
                            putInt("producer_other_requests", server.unexpectedQueries.get())
                        },
                    )
                    return@use
                }
                click(awaitLabel("Media A"))
                awaitTitles(listOf(titles[0], titles[2]), titles)
                assertEquals(10L, active.preferences.mediaId)
                click(awaitLabel("Media B"))
                awaitTitles(listOf(titles[1]), titles)
                assertEquals(20L, active.preferences.mediaId)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(titles, titles)
                click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                click(awaitLabel(context.stringResource(MR.strings.shelf_sort_descending)))
                click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                awaitTitles(titles.reversed(), titles)
                click(awaitLabel("Media A"))
                awaitTitles(listOf(titles[2], titles[0]), titles)

                // Exercise the production deletion event while the actual shelf remains mounted.
                downloads.deleteManga(stored[2], active, removeQueued = false)
                awaitTitles(listOf(titles[0]), titles)
                assertTrue(!files.getValue(stored[2].id).exists())
                assertEquals(0, downloads.getDownloadCount(stored[2]))
                click(awaitLabel("Media B"))
                awaitTitles(listOf(titles[1]), titles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(listOf(titles[1], titles[0]), titles)
                for (manga in stored.take(2)) {
                    assertTrue(files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed))
                }
                assertEquals(
                    "Downloaded shelf must not backfill any remote catalogue or cover",
                    0,
                    server.requests.get(),
                )
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "downloaded_ui",
                            "media=10,20,all;sort=asc,desc;delete=production-event;remaining-bytes=identical",
                        )
                        putInt("remote_requests", server.requests.get())
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active ->
                        for (manga in stored) {
                            check(manga.source == profile.id)
                            downloads.deleteManga(manga, active, removeQueued = false)
                            withTimeout(10_000) {
                                while (downloads.getDownloadCount(manga) != 0 ||
                                    provider.findMangaDirs(manga.title, active).isNotEmpty()
                                ) {
                                    delay(25)
                                }
                            }
                        }
                        withTimeout(10_000) {
                            while (provider.findOwnedSourceDirs(active).isNotEmpty()) delay(25)
                        }
                        active.close()
                    }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    repository.removeConnection(profile.id)
                    assertTrue(connections.getProfiles().none { it.id == profile.id })
                    assertTrue("Retired IDs must remain reserved", connections.isKnownConnectionId(profile.id))
                    assertTrue(mangas.getMangaBySourceId(profile.id).isEmpty())
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("downloaded_ui_cleanup", "owned-profile-files-sql-removed")
                        },
                    )
                } finally {
                    restores.asReversed().forEach { it() }
                    if (hadActive) {
                        connections.activeConnectionId.set(
                            previousActive,
                        )
                    } else {
                        connections.activeConnectionId.delete()
                    }
                    server.close()
                }
            }
        }
    }

    private suspend fun awaitTitles(expected: List<String>, all: List<String>) {
        var actual = emptyList<String>()
        try {
            withTimeout(15_000) {
                while (true) {
                    actual = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                        .filter { it.isVisibleToUser && it.text?.toString() in all }
                        .sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
                        .map { it.text.toString() }.toList()
                    if (actual == expected) return@withTimeout
                    delay(100)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Expected shelf $expected; observed $actual", error)
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("downloaded_ui_stage", actual.joinToString(",")) })
    }

    private suspend fun awaitLabel(
        label: String,
        textOnly: Boolean = false,
        scroll: Boolean = false,
    ): AccessibilityNodeInfo = withTimeout(
        15_000,
    ) {
        while (true) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser &&
                    (it.text?.toString() == label || (!textOnly && it.contentDescription?.toString() == label))
            }?.let { return@withTimeout it }
            if (scroll) {
                nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                    it.isVisibleToUser && it.isScrollable
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            delay(100)
        }
        error("Unreachable")
    }

    private fun nodes(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            yield(root)
            for (index in 0 until root.childCount) yieldAll(nodes(root.getChild(index)))
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun archiveBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("001.png"))
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
            } finally {
                bitmap.recycle()
            }
            zip.closeEntry()
        }
    }.toByteArray()

    private class UnexpectedRequestServer(private val cover: ByteArray? = null) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()

        @Volatile var coverPaths: Set<String> = emptySet()

        @Volatile var loginPath: String? = null
        val coverQueries = AtomicInteger()
        val loginQueries = AtomicInteger()
        val unexpectedQueries = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            if (cover != null) {
                                it.soTimeout = 5_000
                                val input = it.getInputStream().bufferedReader()
                                val request = input.readLine().orEmpty()
                                while (!input.readLine().isNullOrEmpty()) Unit
                                val parts = request.split(' ')
                                val allowed = parts.size == 3 && parts[0] == "GET" && parts[1] in coverPaths &&
                                    parts[2] == "HTTP/1.1"
                                val login = parts.size == 3 && parts[0] == "POST" && parts[1] == loginPath &&
                                    parts[2] == "HTTP/1.1"
                                when {
                                    allowed -> coverQueries.incrementAndGet()
                                    login -> loginQueries.incrementAndGet()
                                    else -> {
                                        unexpectedQueries.incrementAndGet()
                                        InstrumentationRegistry.getInstrumentation().sendStatus(
                                            0,
                                            Bundle().apply { putString("unexpected_fixture_request", request) },
                                        )
                                    }
                                }
                                val bytes = if (allowed) cover else byteArrayOf()
                                val status = if (allowed) "200 OK" else "503 Fixture"
                                it.getOutputStream().write(
                                    (
                                        "HTTP/1.1 $status\r\nContent-Type: image/png\r\n" +
                                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                                it.getOutputStream().write(bytes)
                            } else {
                                it.getOutputStream().write(
                                    (
                                        "HTTP/1.1 503 Fixture\r\nContent-Length: 0\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun persistedCatalogueFiltersAndSortsDownloadsWithoutNetwork(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val repository = Injekt.get<SmangaRepository>()
        val id = System.nanoTime()
        val account = "download-test"
        assertTrue(repository.cachedCatalogue(id, account).isEmpty())
        assertTrue(repository.cachedCatalogue(id + 1, account).isEmpty())
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            error("Downloaded metadata must not fetch remote data")
        }.build()
        val api = SmangaApi(client, Json, "http://127.0.0.1:9", "fixture", "fixture", account)
        try {
            val values = listOf(
                SmangaManga(1, 10, "Same", updatedAt = 200, sortName = "Z"),
                SmangaManga(2, 20, "Same", updatedAt = 100, sortName = "A"),
            )
            val payload = Json.encodeToString(SmangaMangaPage(values, 1, 100, 2))
            repository.putCache(id, account, "shelf/10/fixture", SmangaCacheEntry("1", payload, 10))
            val foreign = Json.encodeToString(SmangaManga(1, 99, "Foreign", updatedAt = 999))
            repository.putCache(id, "other-account", "manga/1", SmangaCacheEntry("data", foreign, 20))
            repository.putCache(id + 1, account, "manga/1", SmangaCacheEntry("data", foreign, 20))
            val cached = SmangaCatalog(id, account, repository, api, Json) {}.downloadedMetadata()
            assertEquals(setOf(1L, 2L), cached.keys)
            assertEquals(10L, cached.getValue(1).mediaId)
            val prefix = "/smanga/$id/$account/"
            val downloaded = (1L..3L).map {
                Manga.create().copy(id = it, source = id, url = "${prefix}manga/$it", title = "Same")
            }
            assertEquals(
                listOf(1L),
                downloadedSmangaShelf(downloaded, prefix, 10, "", "updateTime desc", cached).map { it.id },
            )
            assertEquals(
                listOf(1L, 2L, 3L),
                downloadedSmangaShelf(downloaded, prefix, 0, "", "updateTime desc", cached).map { it.id },
            )
            assertTrue(downloadedSmangaShelf(downloaded.drop(1), prefix, 10, "", "mangaName asc", cached).isEmpty())
            assertEquals(0, requests.get())
        } finally {
            api.close()
            repository.removeConnection(id)
            repository.removeConnection(id + 1)
        }
    }
}
