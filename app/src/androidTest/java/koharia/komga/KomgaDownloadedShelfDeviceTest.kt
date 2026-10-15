package koharia.komga

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
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.domain.manga.model.toDomainManga
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.SeriesMetadataDto
import koharia.komga.api.dto.toSManga
import koharia.komga.ui.library.KomgaLibraryScreen
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaSource
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TypeSelect
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
class KomgaDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun downloadedShelfUiChangesMediaSortAndRespondsToDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        val detailsRoute = InstrumentationRegistry.getArguments().getString("ordinaryDetailsRoute")
        check(detailsRoute == null || detailsRoute in setOf("series", "book_details", "book_preview"))
        val bookRoute = detailsRoute?.startsWith("book_") == true
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val classifications = Injekt.get<KomgaLibraryClassificationManager>()
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
        val profile = profiles.add("komga", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val chapters = Injekt.get<ChapterRepository>()
        val units = mutableMapOf<Long, Chapter>()
        val files = mutableMapOf<Long, UniFile>()
        var source: KomgaSource? = null
        val server = UnexpectedRequestServer()
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, true)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            if (detailsRoute != null) {
                replace(Injekt.get<LibraryPreferences>().chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
                replace(
                    Injekt.get<EntryOpenPreferences>().komgaSingleBook,
                    if (detailsRoute == "book_preview") EntryOpenMode.PAGE_PREVIEW.name else EntryOpenMode.DETAILS.name,
                )
            }
            connections.activeConnectionId.set(profile.id)
            val preferences = sourcePreferences("source_${profile.id}")
            check(preferences.all.isEmpty())
            check(
                preferences.edit().putString("Address", server.address)
                    .putString("AuthMode", "Credentials").putString("Username", "fixture")
                    .putString("Password", "fixture").commit(),
            )
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is KomgaSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as KomgaSource
            }
            source = active
            check(!active.usesSharedDownloadStorage)
            check(provider.findOwnedSourceDirs(active).isEmpty())
            val titles = listOf("UI download A", "UI download B", "UI download C")
            val serverOnlyTitle = "Not locally downloaded"
            val allTitles = titles + serverOnlyTitle
            classifications.updateLibraries(
                profile.id,
                listOf(LibraryDto("10", "Library A"), LibraryDto("20", "Library B")),
            )
            val initialFilters = active.getFilterList()
            initialFilters.filterIsInstance<SeriesSort>().single().state = Filter.Sort.Selection(1, true)
            if (bookRoute) initialFilters.filterIsInstance<TypeSelect>().single().state = TYPE_ALL_INDEX
            active.saveSessionFilterState(initialFilters)
            val remote = (titles + serverOnlyTitle).mapIndexed { index, title ->
                SeriesDto(
                    id = (index + 1).toString(),
                    libraryId = if (index == 1) "20" else "10",
                    name = title,
                    created = "2026-01-0${listOf(2, 1, 3, 4)[index]}T00:00:00Z",
                    lastModified = "2026-02-0${listOf(1, 3, 2, 4)[index]}T00:00:00Z",
                    fileLastModified = "2026-03-01T00:00:00Z",
                    booksCount = 1,
                    metadata = SeriesMetadataDto(title = title, titleSort = listOf("B", "C", "A", "D")[index]),
                )
            }
            val seed = archiveBytes()
            val seeds = remote.reversed().map {
                it.toSManga(server.address).apply { thumbnail_url = null }.toDomainManga(profile.id)
            }
            for (entry in seeds) {
                val prepared = if (detailsRoute == null) {
                    entry
                } else {
                    entry.copy(
                        favorite = true,
                        initialized = true,
                        lastUpdate = System.currentTimeMillis(),
                        url = if (bookRoute &&
                            entry.title == titles[0]
                        ) {
                            "${server.address}/api/v1/books/owned"
                        } else {
                            entry.url
                        },
                    )
                }
                val manga = mangas.insertNetworkManga(listOf(prepared.copy(id = -1))).single()
                stored += manga
                if (detailsRoute != null) {
                    check(!active.shouldRefreshMangaDetails(manga) && !active.shouldRefreshChapters(manga))
                    units[manga.id] = chapters.addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = if (bookRoute &&
                                    entry.title == titles[0]
                                ) {
                                    manga.url
                                } else {
                                    "${server.address}/api/v1/books/unit-${manga.id}"
                                },
                                name = "Owned unit ${manga.id}",
                            ),
                        ),
                    ).single()
                }
                if (manga.title == serverOnlyTitle) {
                    assertEquals(0, downloads.getDownloadCount(manga))
                    continue
                }
                check(provider.findMangaDirs(manga.title, active).isEmpty())
                val directory = provider.getMangaDir(manga.title, active).getOrThrow()
                check(directory.parentFile?.name == active.downloadDirectoryName())
                val unit = units[manga.id]
                val fileName = if (unit ==
                    null
                ) {
                    "fixture.cbz"
                } else {
                    provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                }
                val file = checkNotNull(directory.createFile(fileName))
                file.openOutputStream().use { it.write(seed) }
                files[manga.id] = file
                cache.addChapter(fileName, directory, manga)
                assertEquals(1, downloads.getDownloadCount(manga))
            }
            stored.sortBy { titles.indexOf(it.title).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(KomgaLibraryScreen(profile.id, null, false)) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                if (detailsRoute != null) {
                    val original = navigator!!.lastItem
                    val selected = stored.first { it.title == titles[0] }
                    val baseline = mangas.getMangaBySourceId(profile.id).associateBy { it.id }
                    val unit = units.getValue(selected.id)
                    click(awaitLabel(selected.title))
                    if (detailsRoute == "book_preview") {
                        withTimeout(15_000) { while (navigator?.lastItem !is LanraragiArchivePreviewScreen) delay(50) }
                        awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1), scroll = true)
                        click(awaitLabel(context.stringResource(MR.strings.entry_open_details)))
                    }
                    withTimeout(15_000) { while (navigator?.lastItem !is MangaScreen) delay(50) }
                    check((navigator!!.lastItem as MangaScreen).mangaId == selected.id)
                    awaitLabel(unit.name, scroll = true)
                    check(mangas.getMangaBySourceId(profile.id).associateBy { it.id } == baseline)
                    units.forEach { (id, expected) -> check(chapters.getChapterByMangaId(id).single() == expected) }
                    click(awaitLabel(context.stringResource(MR.strings.action_bar_up_description)))
                    if (detailsRoute == "book_preview") {
                        withTimeout(15_000) { while (navigator?.lastItem !is LanraragiArchivePreviewScreen) delay(50) }
                        awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1), scroll = true)
                        click(awaitLabel(context.stringResource(MR.strings.action_bar_up_description)))
                    }
                    withTimeout(15_000) { while (navigator?.lastItem !== original) delay(50) }
                    awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                    check(mangas.getMangaBySourceId(profile.id).associateBy { it.id } == baseline)
                    units.forEach { (id, expected) -> check(chapters.getChapterByMangaId(id).single() == expected) }
                    files.values.forEach {
                        check(it.openInputStream().use { input -> input.readBytes() }.contentEquals(seed))
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "ordinary_details_route",
                                "$detailsRoute;card;MangaScreen;canonical-owner;SQL-files-retained;returned",
                            )
                            putInt("fixture_refused_requests", server.requests.get())
                        },
                    )
                    return@use
                }
                click(awaitLabel("Library A"))
                awaitTitles(listOf(titles[2], titles[0]), allTitles)
                click(awaitLabel("Library B"))
                awaitTitles(listOf(titles[1]), allTitles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                suspend fun selectSort(label: String) {
                    click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                    click(awaitLabel(context.stringResource(MR.strings.action_sort), scroll = true))
                    click(awaitLabel(label, scroll = true))
                    click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                }
                selectSort(context.stringResource(MR.strings.komga_filter_sort_alphabetically))
                awaitTitles(listOf(titles[1], titles[0], titles[2]), allTitles)
                selectSort(context.stringResource(MR.strings.komga_filter_sort_date_added))
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                selectSort(context.stringResource(MR.strings.komga_filter_sort_date_updated))
                awaitTitles(listOf(titles[1], titles[2], titles[0]), allTitles)
                selectSort(context.stringResource(MR.strings.komga_filter_sort_alphabetically))
                awaitTitles(listOf(titles[1], titles[0], titles[2]), allTitles)
                click(awaitLabel("Library A"))
                awaitTitles(listOf(titles[0], titles[2]), allTitles)

                // Exercise the production deletion event while the actual shelf remains mounted.
                downloads.deleteManga(stored[2], active, removeQueued = false)
                awaitTitles(listOf(titles[0]), allTitles)
                assertTrue(!files.getValue(stored[2].id).exists())
                assertEquals(0, downloads.getDownloadCount(stored[2]))
                click(awaitLabel("Library B"))
                awaitTitles(listOf(titles[1]), allTitles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(listOf(titles[1], titles[0]), allTitles)
                for (manga in stored.filter { it.id != stored[2].id && it.id in files }) {
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
                            "libraries=10,20,all;sort=titleSort,added,updated;nonlocal=excluded;delete=production-event",
                        )
                        putInt("remote_requests", server.requests.get())
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active ->
                        for (manga in stored.filter { it.id in files }) {
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
                    }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    assertTrue(classifications.getLibraries(profile.id).isEmpty())
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

    private class UnexpectedRequestServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            it.getOutputStream().write(
                                "HTTP/1.1 503 Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
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
