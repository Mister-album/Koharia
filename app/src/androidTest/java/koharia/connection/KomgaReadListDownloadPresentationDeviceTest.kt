package koharia.connection

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.dto.KomgaOfflineFilterMetadata
import koharia.komga.api.dto.withOfflineFilterMetadata
import koharia.komga.ui.organization.KomgaOrganizationScreen
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Test
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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class KomgaReadListDownloadPresentationDeviceTest {
    @Test
    fun actualReadListMapsSeriesDownloadsToSingleBookCardsAndDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val detailsMode = InstrumentationRegistry.getArguments().getString("readlistDetailsMode")
            ?.let(EntryOpenMode::valueOf)
        check(detailsMode == null || detailsMode in setOf(EntryOpenMode.DETAILS, EntryOpenMode.PAGE_PREVIEW))
        check(context.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previous = connections.activeConnectionId.get()
        val hadPrevious = connections.activeConnectionId.isSet()
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val old = preference.get()
            restores += { if (present) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val profile = profiles.add("komga", "Download states ${UUID.randomUUID()}")
        val server = Catalogue(archive())
        var source: KomgaSource? = null
        var stored: Manga? = null
        try {
            replace(libraryPreferences.showLibraryReadProgress, false)
            replace(libraryPreferences.showShelfDownloadCount, true)
            replace(libraryPreferences.displayMode, LibraryDisplayMode.List)
            if (detailsMode != null) {
                replace(Injekt.get<EntryOpenPreferences>().komgaSingleBook, detailsMode.name)
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, false)
                replace(libraryPreferences.chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
            }
            val preferences = sourcePreferences("source_${profile.id}")
            check(preferences.all.isEmpty())
            check(preferences.edit().putString("Address", server.address).commit())
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is KomgaSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as KomgaSource
            }
            source = active
            check(!active.usesSharedDownloadStorage && provider.findOwnedSourceDirs(active).isEmpty())
            val manga = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = profile.id,
                        url = "${server.address}/api/v1/series/owned",
                        title = "Owned download states",
                        favorite = detailsMode != null,
                        initialized = detailsMode != null,
                        lastUpdate = if (detailsMode != null) System.currentTimeMillis() else 0,
                        memo = JsonObject(emptyMap()).let {
                            if (detailsMode != null) it.withOfflineFilterMetadata(KomgaOfflineFilterMetadata()) else it
                        },
                    ),
                ),
            ).single()
            stored = manga
            if (detailsMode != null) {
                check(
                    manga.initialized && !active.shouldRefreshMangaDetails(manga) &&
                        !active.shouldRefreshChapters(manga),
                )
            }
            val units = chapters.addAll(
                (1..2).map {
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "${server.address}/api/v1/books/book-$it",
                        name = "Unit $it",
                    )
                },
            )
            val seed = archive()
            val complete = context.stringResource(MR.strings.shelf_download_complete, 1L)
            val expectedTitles = listOf("Owned book 1", "Owned book 2")
            val directory = provider.getMangaDir(manga.title, active).getOrThrow()
            suspend fun add(unit: Chapter) {
                val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                check(directory.findFile(name) == null)
                val file = checkNotNull(directory.createFile(name))
                file.openOutputStream().use { it.write(seed) }
                cache.addChapter(name, directory, manga)
            }
            suspend fun remove(unit: Chapter) {
                val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                val file = checkNotNull(directory.findFile(name))
                check(file.openInputStream().use { it.readBytes() }.contentEquals(seed))
                check(file.delete())
                cache.removeChapter(unit, manga)
            }
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(KomgaOrganizationScreen(profile.id, KomgaOrganizationKind.READ_LIST, "owned")) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                suspend fun awaitCards(count: Int, stage: String) {
                    var actual = emptyList<String>()
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                actual = labels(instrumentation.uiAutomation.rootInActiveWindow)
                                if (expectedTitles.all { it in actual } && actual.count { it == complete } == count) {
                                    break
                                }
                                delay(50)
                            }
                        }
                    } catch (error: Exception) {
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply { putString("readlist_failure_labels", actual.toString()) },
                        )
                        throw error
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putString("readlist_download_stage", "$stage;complete=$count") },
                    )
                }
                awaitCards(0, "none")
                if (detailsMode != null) {
                    fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                        if (node == null) return emptyList()
                        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
                    }
                    suspend fun awaitLabel(text: String, scroll: Boolean = false): AccessibilityNodeInfo {
                        try {
                            return withTimeout(15_000) {
                                var found: AccessibilityNodeInfo? = null
                                while (found == null) {
                                    val visible = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                                    found = visible.firstOrNull {
                                        it.isVisibleToUser &&
                                            (it.text?.toString() == text || it.contentDescription?.toString() == text)
                                    }
                                    if (found == null) {
                                        if (scroll) {
                                            visible.firstOrNull { it.isScrollable && it.isVisibleToUser }
                                                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                        }
                                        delay(50)
                                    }
                                }
                                found
                            }
                        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "readlist_details_timeout",
                                        "$detailsMode;waiting=$text;" +
                                            "screen=${navigator?.lastItem?.javaClass?.simpleName};" +
                                            "labels=${labels(instrumentation.uiAutomation.rootInActiveWindow)}",
                                    )
                                },
                            )
                            throw error
                        }
                    }
                    fun click(node: AccessibilityNodeInfo) {
                        var target = node
                        while (!target.isClickable) target = checkNotNull(target.parent)
                        check(target.isVisibleToUser && target.isEnabled)
                        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    add(units[1])
                    awaitCards(1, "second-book-retained")
                    val original = navigator!!.lastItem
                    val baseline = mangas.getMangaById(manga.id)
                    click(awaitLabel(expectedTitles[1]))
                    if (detailsMode == EntryOpenMode.PAGE_PREVIEW) {
                        withTimeout(15_000) { while (navigator?.lastItem !is LanraragiArchivePreviewScreen) delay(50) }
                        awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1), scroll = true)
                        click(awaitLabel(context.stringResource(MR.strings.entry_open_details)))
                    }
                    withTimeout(15_000) { while (navigator?.lastItem !is MangaScreen) delay(50) }
                    check((navigator!!.lastItem as MangaScreen).mangaId == manga.id)
                    units.forEach { awaitLabel(it.name, scroll = true) }
                    check(mangas.getMangaBySourceId(profile.id).single() == baseline) {
                        "Owned fixture manga changed: before=$baseline; after=${mangas.getMangaById(manga.id)}"
                    }
                    check(chapters.getChapterByMangaId(manga.id).associateBy { it.id } == units.associateBy { it.id })
                    click(awaitLabel(context.stringResource(MR.strings.action_bar_up_description)))
                    if (detailsMode == EntryOpenMode.PAGE_PREVIEW) {
                        withTimeout(15_000) { while (navigator?.lastItem !is LanraragiArchivePreviewScreen) delay(50) }
                        awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1), scroll = true)
                        click(awaitLabel(context.stringResource(MR.strings.action_bar_up_description)))
                    }
                    withTimeout(15_000) { while (navigator?.lastItem !== original) delay(50) }
                    awaitCards(1, "returned-from-details")
                    check(mangas.getMangaBySourceId(profile.id).single() == baseline)
                    check(chapters.getChapterByMangaId(manga.id).associateBy { it.id } == units.associateBy { it.id })
                    remove(units[1])
                    check(server.writes.get() == 0 && server.unknown.get() == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "readlist_details_route",
                                "$detailsMode;second-book;original-series;two-units;SQL-files-retained;returned",
                            )
                            putString(
                                "readlist_details_protocol",
                                "unknown=0;writes=0;seriesReads=${server.seriesReads.get()}",
                            )
                        },
                    )
                    return@use
                }
                add(units[0])
                awaitCards(1, "series-first-book")
                add(units[1])
                awaitCards(2, "series-both-books")
                libraryPreferences.displayMode.set(LibraryDisplayMode.CompactGrid)
                awaitCards(2, "grid-both-books")
                libraryPreferences.showLibraryReadProgress.set(true)
                awaitCards(2, "reading-progress-independent")
                remove(units[0])
                awaitCards(1, "deleted-first")
                remove(units[1])
                awaitCards(0, "deleted-all")
                check(downloads.getDownloadCount(manga) == 0)
                check(server.writes.get() == 0)
                check(server.unknown.get() == 0)
                check(server.bookReads.get() == 1)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("readlist_protocol", "bookReads=${server.bookReads.get()};writes=0;unknown=0")
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active -> stored?.let { downloads.deleteManga(it, active, removeQueued = false) } }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    check(connections.getProfiles().none { it.id == profile.id })
                    check(mangas.getMangaBySourceId(profile.id).isEmpty())
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("readlist_download_cleanup", "complete")
                        },
                    )
                } finally {
                    server.close()
                    restores.asReversed().forEach { it() }
                    if (hadPrevious) {
                        connections.activeConnectionId.set(
                            previous,
                        )
                    } else {
                        connections.activeConnectionId.delete()
                    }
                }
            }
        }
    }

    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return buildList {
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(::add)
                node.contentDescription?.toString()?.let(::add)
            }
            repeat(node.childCount) { addAll(labels(node.getChild(it))) }
        }
    }

    private fun archive(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("001.png").apply { time = 1577836800000L })
                zip.write(png)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private class Catalogue(seed: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val address = "http://127.0.0.1:${socket.localPort}"
        val writes = AtomicInteger()
        val unknown = AtomicInteger()
        val bookReads = AtomicInteger()
        val seriesReads = AtomicInteger()
        private val cover = ZipInputStream(seed.inputStream()).use {
            checkNotNull(it.nextEntry)
            it.readBytes()
        }
        init {
            executor.execute {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        runCatching {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine()?.split(' ') ?: return@runCatching
                            if (request.size < 2) return@runCatching
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val method = request[0]
                            val path = request[1].substringBefore('?')
                            if (method != "GET") writes.incrementAndGet()
                            var media = "application/json"
                            val body = when {
                                method != "GET" -> "{}".encodeToByteArray()
                                path.endsWith("/thumbnail") -> {
                                    media = "image/png"
                                    cover
                                }
                                path == "/api/v1/users/me" ->
                                    """{"id":"owned","email":"fixture@example.invalid",
                                    "roles":["FILE_DOWNLOAD"]}
                                    """.trimIndent().encodeToByteArray()
                                path == "/api/v1/libraries" -> "[]".encodeToByteArray()
                                path == "/api/v1/readlists/owned" ->
                                    """{"id":"owned","name":"Owned read list",
                                    "bookIds":["book-1","book-2"]}
                                    """.trimIndent().encodeToByteArray()
                                path in setOf("/api/v1/readlists/owned/books", "/api/v1/series/owned/books") -> {
                                    if (path.contains(
                                            "/readlists/",
                                        )
                                    ) {
                                        bookReads.incrementAndGet()
                                    } else {
                                        seriesReads.incrementAndGet()
                                    }
                                    val content = (1..2).joinToString(",") { number ->
                                        """{"id":"book-$number","seriesId":"owned","name":"Owned book $number",
                                        "fileLastModified":"2026-01-01T00:00:00Z",
                                        "metadata":{"title":"Owned book $number"},
                                        "media":{"pagesCount":1}}
                                        """.trimIndent()
                                    }
                                    """{"content":[$content],"totalPages":1,"last":true,"totalElements":2}"""
                                        .encodeToByteArray()
                                }
                                else -> {
                                    unknown.incrementAndGet()
                                    "{}".encodeToByteArray()
                                }
                            }
                            it.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: $media\r\nContent-Length: ${body.size}\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).encodeToByteArray(),
                                )
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
}
