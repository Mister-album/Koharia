package koharia.suwayomi

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
import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.ui.SuwayomiLibraryScreen
import koharia.suwayomi.ui.SuwayomiSourceScreen
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
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
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SuwayomiDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun downloadedShelfUiChangesMediaSortAndRespondsToDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val producerRoute = InstrumentationRegistry.getArguments().getString("shelfCountProducer") == "true"
        val detailsRoute = InstrumentationRegistry.getArguments().getString("standardDetailsRoute") == "true"
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val repository = Injekt.get<SuwayomiRepository>()
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
        val profile = profiles.add("suwayomi", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val files = mutableMapOf<Long, UniFile>()
        val extraFiles = mutableListOf<UniFile>()
        val chapters = Injekt.get<ChapterRepository>()
        val units = mutableMapOf<Long, List<Chapter>>()
        var source: SuwayomiSource? = null
        val server = UnexpectedRequestServer(allowFilters = producerRoute || detailsRoute)
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, !producerRoute)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            if (detailsRoute) {
                replace(
                    Injekt.get<LibraryPreferences>().chapterCoverDisplayMode,
                    Manga.CHAPTER_COVER_DISPLAY_TEXT,
                )
            }
            connections.activeConnectionId.set(profile.id)
            SuwayomiPreferences(profile.id).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is SuwayomiSource) delay(25)
                (Injekt.get<SourceManager>().get(profile.id) as SuwayomiSource).also { it.reload() }
            }
            source = active
            check(provider.findSourceDirs(active).isEmpty())
            val session = active.session()
            val titles = listOf("UI download A", "UI download B", "UI download C")
            val serverOnlyTitle = "Server-only download"
            val foreignTitle = "Foreign account download"
            val allTitles = titles + listOf(serverOnlyTitle, foreignTitle)
            val categories = listOf(SuwayomiCategory(10, "Category A"), SuwayomiCategory(20, "Category B"))
            val remote = titles.mapIndexed { index, title ->
                SuwayomiManga(
                    id = index + 1,
                    title = title,
                    author = listOf("Writer B", "Writer C", "Writer A")[index],
                    inLibraryAt = listOf(200L, 100L, 300L)[index],
                    categories = SuwayomiNodes(listOf(categories[if (index == 1) 1 else 0])),
                    chapters = SuwayomiNodes(totalCount = 3),
                )
            }
            val serverOnly = SuwayomiManga(
                4,
                serverOnlyTitle,
                downloadCount = 100,
                categories = SuwayomiNodes(listOf(categories[0])),
            )
            val shelf = SuwayomiShelf(remote + serverOnly, categories)
            repository.putCache(
                profile.id,
                session.identity.account,
                "shelf",
                SuwayomiCacheEntry("snapshot", Json.encodeToString(shelf), 1),
            )
            val foreignIdentity = SuwayomiIdentity(profile.id, "f".repeat(64))
            check(foreignIdentity.account != session.identity.account)
            repository.putCache(
                profile.id,
                foreignIdentity.account,
                "shelf",
                SuwayomiCacheEntry("snapshot", Json.encodeToString(shelf.copy(mangas = remote.reversed())), 2),
            )
            if (producerRoute) {
                val directory = object : SuwayomiService by SuwayomiBrowseCacheDeviceTest.DirectoryFixture() {
                    override suspend fun discoverSourceManga(
                        sourceId: Long,
                        page: Int,
                        query: String?,
                        type: SuwayomiSourceMangaType,
                        filters: List<SuwayomiFilterChange>,
                    ) = SuwayomiSourcePage(remote, false)
                }
                val seedCatalog = SuwayomiCatalog(session.identity, repository, directory, Json, session::checkActive)
                seedCatalog.sources()
                seedCatalog.sourceListing(
                    12,
                    1,
                    null,
                    SuwayomiSourceMangaType.POPULAR,
                    emptyList(),
                    seedCatalog.listingRevision(),
                )
            }
            if (detailsRoute) {
                for (entry in remote) {
                    repository.putCache(
                        profile.id,
                        session.identity.account,
                        "manga",
                        SuwayomiCacheEntry(entry.id.toString(), Json.encodeToString(entry), 1),
                    )
                    val remoteUnits = (1..3).map { number ->
                        SuwayomiChapter(entry.id * 10 + number, entry.id, "Owned unit $number", pageCount = 1)
                    }
                    repository.putCache(
                        profile.id,
                        session.identity.account,
                        "chapters",
                        SuwayomiCacheEntry(entry.id.toString(), Json.encodeToString(remoteUnits), 1),
                    )
                    check(session.catalog.hasPreparedManga(entry.id))
                }
            }
            val seed = archiveBytes()
            val seeds = remote.reversed().map { active.toManga(it, session) } + listOf(
                Manga.create().copy(source = profile.id, url = foreignIdentity.mangaUrl(1), title = foreignTitle),
                active.toManga(serverOnly, session),
            )
            for (entry in seeds) {
                val manga = mangas.insertNetworkManga(
                    listOf(
                        entry.copy(
                            id = -1,
                            favorite = true,
                            initialized = detailsRoute,
                            memo = JsonObject(entry.memo + ("booksCount" to JsonPrimitive(2))),
                        ),
                    ),
                ).single()
                stored += manga
                if (detailsRoute && manga.title in titles) {
                    val remoteId = session.identity.mangaId(manga.url)
                    units[manga.id] = chapters.addAll(
                        (1..3).map { number ->
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = session.identity.chapterUrl(
                                    remoteId,
                                    remoteId * 10 + number,
                                ),
                                name = "Owned unit $number",
                            )
                        },
                    )
                }
                if (manga.title == serverOnlyTitle) {
                    assertEquals(0, downloads.getDownloadCount(manga))
                    continue
                }
                check(provider.findMangaDirs(manga.title, active).isEmpty())
                val directory = provider.getMangaDir(manga.title, active).getOrThrow()
                check(directory.parentFile?.name == active.downloadDirectoryName())
                val unit = units[manga.id]?.first()
                val fileName =
                    unit?.let { provider.getChapterDirName(it.name, it.scanlator, it.url) + ".cbz" } ?: "fixture.cbz"
                val file = checkNotNull(directory.createFile(fileName))
                file.openOutputStream().use { it.write(seed) }
                files[manga.id] = file
                cache.addChapter(fileName, directory, manga)
                if (detailsRoute && !producerRoute && manga.title == titles[0]) {
                    for (extraUnit in units.getValue(manga.id).drop(1)) {
                        val name =
                            provider.getChapterDirName(extraUnit.name, extraUnit.scanlator, extraUnit.url) + ".cbz"
                        check(directory.findFile(name) == null)
                        val extra = checkNotNull(directory.createFile(name))
                        extra.openOutputStream().use { it.write(seed) }
                        extraFiles += extra
                        cache.addChapter(name, directory, manga)
                    }
                }
                assertEquals(
                    if (detailsRoute && !producerRoute && manga.title == titles[0]) 3 else 1,
                    downloads.getDownloadCount(manga),
                )
            }
            stored.sortBy { titles.indexOf(it.title).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(
                                if (producerRoute) {
                                    SuwayomiSourceScreen(profile.id, 12)
                                } else {
                                    SuwayomiLibraryScreen(profile.id, null, false)
                                },
                            ) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                awaitTitles(titles, allTitles)
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
                    assertEquals(
                        if (detailsRoute && !producerRoute && original.title == titles[0]) 3 else 1,
                        downloads.getDownloadCount(refreshed),
                    )
                }
                val manualCounts = if (detailsRoute && !producerRoute) "3,1,1" else "1"
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "shelf_count_persistence",
                            "old=2;cache=3;sql=3;manual=$manualCounts;favorites=retained",
                        )
                    },
                )
                if (detailsRoute) {
                    val original = navigator!!.lastItem
                    val selected = stored.first { it.title == titles[0] }
                    val baseline = mangas.getMangaBySourceId(profile.id).associateBy { it.id }
                    click(awaitLabel(selected.title))
                    withTimeout(15_000) { while (navigator?.lastItem !is MangaScreen) delay(50) }
                    check((navigator!!.lastItem as MangaScreen).mangaId == selected.id)
                    units.getValue(selected.id).forEach { awaitLabel(it.name, scroll = true) }
                    check(mangas.getMangaBySourceId(profile.id).associateBy { it.id } == baseline)
                    units.forEach { (id, expected) ->
                        check(chapters.getChapterByMangaId(id).associateBy { it.id } == expected.associateBy { it.id })
                    }
                    click(awaitLabel(context.stringResource(MR.strings.action_bar_up_description)))
                    withTimeout(15_000) { while (navigator?.lastItem !== original) delay(50) }
                    awaitTitles(titles, allTitles)
                    check(mangas.getMangaBySourceId(profile.id).associateBy { it.id } == baseline)
                    units.forEach { (id, expected) ->
                        check(chapters.getChapterByMangaId(id).associateBy { it.id } == expected.associateBy { it.id })
                    }
                    files.values.forEach {
                        check(it.openInputStream().use { input -> input.readBytes() }.contentEquals(seed))
                    }
                    extraFiles.forEach {
                        check(it.openInputStream().use { input -> input.readBytes() }.contentEquals(seed))
                    }
                    check(server.mutations.get() == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "standard_details_route",
                                "${if (producerRoute) "source" else "library"};" +
                                    "card;MangaScreen;canonical-owner;SQL-files-retained;returned",
                            )
                            putString(
                                "standard_details_requests",
                                "filters=${server.filterQueries.get()};" +
                                    "refusedOtherQueries=${server.unexpectedQueries.get()};mutations=0",
                            )
                        },
                    )
                    return@use
                }
                if (producerRoute) {
                    awaitLabel(context.stringResource(MR.strings.shelf_download_partial, 1L, 3L))
                    withTimeout(10_000) { while (server.filterQueries.get() == 0) delay(25) }
                    assertEquals(0, server.unexpectedQueries.get())
                    assertEquals(server.requests.get(), server.filterQueries.get())
                    for (manga in stored.filter { it.title in titles }) {
                        assertTrue(
                            files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed),
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
                        },
                    )
                    return@use
                }
                click(awaitLabel("Category A"))
                awaitTitles(listOf(titles[0], titles[2]), allTitles)
                assertEquals(10, active.preferences.categoryId)
                click(awaitLabel("Category B"))
                awaitTitles(listOf(titles[1]), allTitles)
                assertEquals(20, active.preferences.categoryId)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(titles, allTitles)
                click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                click(awaitLabel(context.stringResource(MR.strings.shelf_sort_descending), scroll = true))
                click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                awaitTitles(titles.reversed(), allTitles)
                suspend fun selectSort(current: String, next: String) {
                    click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                    click(awaitLabel(current, scroll = true))
                    click(awaitLabel(next))
                    click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                }
                selectSort(
                    context.stringResource(MR.strings.suwayomi_sort_name),
                    context.stringResource(MR.strings.suwayomi_sort_author),
                )
                awaitTitles(listOf(titles[1], titles[0], titles[2]), allTitles)
                selectSort(
                    context.stringResource(MR.strings.suwayomi_sort_author),
                    context.stringResource(MR.strings.suwayomi_sort_added),
                )
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                selectSort(
                    context.stringResource(MR.strings.suwayomi_sort_added),
                    context.stringResource(MR.strings.suwayomi_sort_name),
                )
                awaitTitles(titles.reversed(), allTitles)
                click(awaitLabel("Category A"))
                awaitTitles(listOf(titles[2], titles[0]), allTitles)

                // Exercise the production deletion event while the actual shelf remains mounted.
                downloads.deleteManga(stored[2], active, removeQueued = false)
                awaitTitles(listOf(titles[0]), allTitles)
                assertTrue(!files.getValue(stored[2].id).exists())
                assertEquals(0, downloads.getDownloadCount(stored[2]))
                click(awaitLabel("Category B"))
                awaitTitles(listOf(titles[1]), allTitles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(listOf(titles[1], titles[0]), allTitles)
                for (manga in stored.filter { it.id != stored[2].id && it.id in files }) {
                    assertTrue(
                        files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed),
                    )
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
                            "categories=10,20,all;sort=title,author,added;foreign-and-server-only=excluded;delete=production-event",
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
            val labels = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                .filter { it.isVisibleToUser && it.packageName?.toString() == "app.koharia.dev.devicefixture" }
                .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.toList()
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("downloaded_ui_failure_labels", labels.toString())
                },
            )
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
            nodes(instrumentation.uiAutomation.rootInActiveWindow).filter {
                it.isVisibleToUser &&
                    (it.text?.toString() == label || (!textOnly && it.contentDescription?.toString() == label))
            }.minByOrNull { Rect().also(it::getBoundsInScreen).top }?.let { return@withTimeout it }
            if (scroll) {
                nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                    it.isVisibleToUser && it.isScrollable
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                instrumentation.waitForIdleSync()
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
        instrumentation.waitForIdleSync()
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

    private class UnexpectedRequestServer(private val allowFilters: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val filterQueries = AtomicInteger()
        val unexpectedQueries = AtomicInteger()
        val mutations = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            if (allowFilters) {
                                it.soTimeout = 5_000
                                val input = it.getInputStream().bufferedReader()
                                val request = input.readLine().orEmpty()
                                val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                                val length = headers.firstOrNull { line -> line.startsWith("Content-Length:", true) }
                                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                                check(length in 1..100_000)
                                val body = CharArray(length)
                                var offset = 0
                                while (offset < length) {
                                    val read = input.read(body, offset, length - offset)
                                    check(read > 0)
                                    offset += read
                                }
                                val query = org.json.JSONObject(String(body)).getString("query")
                                if (query.trimStart().startsWith("mutation")) mutations.incrementAndGet()
                                val allowed = request.startsWith("POST ") && query.startsWith("query(") &&
                                    query.contains("source(id:") && query.contains("filters{")
                                if (allowed) filterQueries.incrementAndGet() else unexpectedQueries.incrementAndGet()
                                val response = if (allowed) "{\"data\":{\"source\":{\"filters\":[]}}}" else "{}"
                                val status = if (allowed) "200 OK" else "503 Fixture"
                                it.getOutputStream().write(
                                    (
                                        "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                            "Content-Length: ${response.length}\r\n" +
                                            "Connection: close\r\n\r\n$response"
                                        ).toByteArray(),
                                )
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
}
