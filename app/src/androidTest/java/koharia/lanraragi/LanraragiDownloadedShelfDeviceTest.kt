package koharia.lanraragi

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
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiRepository
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
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
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LanraragiDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun downloadedShelfUiChangesMediaSortAndRespondsToDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        val previewDetails = InstrumentationRegistry.getArguments().getString("previewDetailsRoute") == "true"
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val repository = Injekt.get<LanraragiRepository>()
        val covers = Injekt.get<CoverCache>()
        val createdCovers = mutableListOf<File>()
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
        val profile = profiles.add("lanraragi", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val files = mutableMapOf<Long, UniFile>()
        var source: LanraragiSource? = null
        val server = UnexpectedRequestServer()
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, true)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            connections.activeConnectionId.set(profile.id)
            LanraragiPreferences(profile.id).save(server.address, "fixture-key")
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
            }
            source = active
            // The owned fixture models an offline connection; the real network monitor is unchanged.
            active.networkAvailable.value = false
            check(provider.findOwnedSourceDirs(active).isEmpty())
            val titles = listOf("UI download A", "UI download B", "UI download C")
            val serverOnlyTitle = "UI download Z - not local"
            val allTitles = titles + serverOnlyTitle
            val remote = allTitles.mapIndexed { index, title ->
                LanraragiEntry(
                    id = UUID.randomUUID().toString().replace("-", "").padEnd(40, '0'),
                    title = title,
                    tags = if (previewDetails) "" else "date_added:${listOf(200, 100, 300, 400)[index]}",
                    pageCount = 1,
                    lastRead = listOf(100L, 300L, 200L, 400L)[index],
                )
            }
            val categories = listOf(
                LanraragiEntry(
                    "category-a",
                    LanraragiEntry.Kind.CATEGORY,
                    "Category A",
                    members = listOf(remote[0].id, remote[2].id),
                ),
                LanraragiEntry(
                    "category-b",
                    LanraragiEntry.Kind.CATEGORY,
                    "Category B",
                    members = listOf(remote[1].id),
                ),
            )
            val generation = System.currentTimeMillis()
            repository.begin(profile.id, generation)
            repository.stage(profile.id, generation, remote + categories)
            repository.publish(profile.id, generation, generation)
            active.preferences.markIndexed(active.preferences.address)
            val seed = archiveBytes()
            val coverBytes = ZipInputStream(seed.inputStream()).use { zip ->
                checkNotNull(zip.nextEntry)
                zip.readBytes()
            }
            val seeds = remote.reversed().map { active.toManga(it, remote + categories) }
            for (entry in seeds) {
                val manga = mangas.insertNetworkManga(listOf(entry.copy(id = -1, favorite = true))).single()
                stored += manga
                assertTrue(manga.favorite)
                val cover = checkNotNull(covers.getCoverFile(manga.source, manga.thumbnailUrl))
                check(!cover.exists())
                cover.writeBytes(coverBytes)
                createdCovers += cover
                if (manga.title == serverOnlyTitle) {
                    assertEquals(0, downloads.getDownloadCount(manga))
                    continue
                }
                check(provider.findMangaDirs(manga.title, active).isEmpty())
                val directory = provider.getMangaDir(manga.title, active).getOrThrow()
                check(directory.parentFile?.name == active.downloadDirectoryName())
                val fileName = if (previewDetails) {
                    val chapter = LanraragiEntryOpenManager().prepareChapter(active, manga)
                    provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz"
                } else {
                    "fixture.cbz"
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
                            Navigator(LanraragiLibraryScreen(profile.id, null, false)) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                awaitTitles(allTitles, allTitles)
                if (previewDetails) {
                    var target: AccessibilityNodeInfo? = awaitLabel(titles[0])
                    while (target != null && !target.isLongClickable) target = target.parent
                    check(target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
                    withTimeout(15_000) {
                        while (navigator?.lastItem !is LanraragiArchivePreviewScreen) delay(50)
                    }
                    awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1))
                    val preview = navigator!!.lastItem
                    click(awaitLabel(context.stringResource(MR.strings.entry_open_details)))
                    withTimeout(15_000) {
                        while (navigator?.lastItem !is MangaScreen) delay(50)
                    }
                    val expected = stored.first { it.title == titles[0] }
                    check((navigator!!.lastItem as MangaScreen).mangaId == expected.id)
                    val details = mangas.getMangaById(expected.id)
                    val units = Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id)
                    check(details.source == profile.id && details.url == expected.url)
                    check(details.author.isNullOrBlank() && details.artist.isNullOrBlank())
                    check(details.description.isNullOrBlank())
                    check(units.single().url == expected.url)
                    check(active.archiveId(units.single().url) == remote[0].id)
                    awaitLabel(details.title)
                    awaitLabel(context.pluralStringResource(MR.plurals.manga_num_chapters, 1, 1), scroll = true)
                    scenario.onActivity { check(navigator!!.pop()) }
                    check(navigator!!.lastItem === preview)
                    awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1))
                    check(files.getValue(expected.id).openInputStream().use { it.readBytes() }.contentEquals(seed))
                    check(server.writes.get() == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "preview_details",
                                "shelf-longpress;page1;canonical-owner;single-archive;empty-metadata;returned",
                            )
                            putInt("preview_details_reads", server.requests.get())
                            putInt("preview_details_writes", server.writes.get())
                        },
                    )
                    return@use
                }
                click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                click(awaitLabel(context.stringResource(MR.strings.lanraragi_downloaded)))
                click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                awaitTitles(titles, allTitles)
                click(awaitLabel("Category A"))
                awaitTitles(listOf(titles[0], titles[2]), allTitles)
                click(awaitLabel("Category B"))
                awaitTitles(listOf(titles[1]), allTitles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(titles, allTitles)
                suspend fun selectSort(label: String) {
                    click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                    click(awaitLabel(context.stringResource(MR.strings.action_sort), scroll = true))
                    click(awaitLabel(label, scroll = true))
                    click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                }
                selectSort(context.stringResource(MR.strings.lanraragi_title))
                awaitTitles(titles.reversed(), allTitles)
                selectSort(context.stringResource(MR.strings.lanraragi_added))
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                selectSort(context.stringResource(MR.strings.lanraragi_last_read))
                awaitTitles(listOf(titles[1], titles[2], titles[0]), allTitles)
                selectSort(context.stringResource(MR.strings.lanraragi_title))
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
                    assertTrue(files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed))
                }
                assertEquals(
                    "Downloaded shelf must not backfill catalogue or cover: ${server.paths}",
                    0,
                    server.requests.get(),
                )
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "downloaded_ui",
                            "categories=a,b,all;sort=title,added,lastRead;offline-fixture;nonlocal=excluded;delete=production-event",
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
                    source?.close()
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    assertTrue(repository.entries(profile.id).isEmpty())
                    for (cover in createdCovers) check(!cover.exists() || cover.delete())
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
        val writes = AtomicInteger()
        val paths = ConcurrentLinkedQueue<String>()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            it.soTimeout = 2_000
                            val request = it.getInputStream().bufferedReader().readLine().orEmpty()
                            paths += request.substringBefore('?')
                            if (!request.startsWith("GET ")) writes.incrementAndGet()
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
