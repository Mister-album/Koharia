package koharia.source.local

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
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
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.connection.LibraryContentScope
import koharia.connection.SharedAppPreferences
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.pluralStringResource
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
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun allStorageModesKeepShelfAvailabilityAndSort(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in LibraryStorageMode.entries) verifyMode(mode)
    }

    @Test
    fun networkWarmShelfSurvivesSourceResumeAndRecreation(): Unit = runBlocking(Dispatchers.IO) {
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) verifyMode(mode, lifecycleOnly = true)
    }

    @Test
    fun networkFolderDownloadIndicatorsFollowFilesInListAndGrid(): Unit = runBlocking(Dispatchers.IO) {
        val requested = InstrumentationRegistry.getArguments().getString("folderStorageMode")
        val modes = requested?.let { listOf(LibraryStorageMode.valueOf(it)) }
            ?: listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)
        check(modes.all { it != LibraryStorageMode.LOCAL })
        for (mode in modes) verifyMode(mode, folderOnly = true)
    }

    @Test
    fun singleFileShelfOpensStandardDetailsWithOriginalOwner(): Unit = runBlocking(Dispatchers.IO) {
        val requested = InstrumentationRegistry.getArguments().getString("detailsStorageMode")
        val modes = requested?.let { listOf(LibraryStorageMode.valueOf(it)) } ?: LibraryStorageMode.entries
        for (mode in modes) verifyMode(mode, detailsOnly = true)
    }

    private suspend fun verifyMode(
        mode: LibraryStorageMode,
        lifecycleOnly: Boolean = false,
        folderOnly: Boolean = false,
        detailsOnly: Boolean = false,
    ) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
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
        val profile = profiles.add("local-folder", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val files = mutableMapOf<Long, UniFile>()
        var source: LocalFolderSource? = null
        val server = UnexpectedRequestServer(mode.takeIf { detailsOnly })
        val nativeRoot = File(context.cacheDir, "local-downloaded-ui-${UUID.randomUUID()}").canonicalFile
        check(nativeRoot.parentFile == context.cacheDir.canonicalFile && !nativeRoot.exists() && nativeRoot.mkdirs())
        val nativeFiles = mutableListOf<File>()
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, !lifecycleOnly && !folderOnly && !detailsOnly)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            val library = Injekt.get<LibraryPreferences>()
            if (detailsOnly) {
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, false)
                replace(Injekt.get<EntryOpenPreferences>().localSingleComic, EntryOpenMode.DETAILS.name)
                replace(library.chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
            }
            if (folderOnly) {
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, false)
                replace(library.chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
                replace(library.chapterCoverGridColumns, 2)
                replace(library.showChapterFileSize, false)
                replace(library.showChapterReadProgress, false)
                replace(library.showShelfDownloadCount, true)
            }
            connections.activeConnectionId.set(profile.id)
            val network = mode != LibraryStorageMode.LOCAL
            val storage = NetworkStoragePreferences(profile.id)
            storage.save(
                NetworkStorageConfiguration(
                    mode = mode,
                    address = if (mode ==
                        LibraryStorageMode.SMB
                    ) {
                        server.address.replace("http://", "smb://") + "/share"
                    } else {
                        server.address +
                            "/root"
                    },
                    rootIdentity = "fixture-${profile.id}",
                ),
                "fixture",
                "fixture",
            )
            val local = LocalLibraryPreferences(profile.id, Json)
            val roots = listOf("a", "b").map { name ->
                val directory = File(nativeRoot, name).apply { check(mkdir()) }
                LocalLibraryRootConfig(
                    id = "root-$name",
                    treeUri = if (network) {
                        DocumentsContract.buildDocumentUri(
                            NetworkStorageRuntime.authority(context),
                            "${profile.id}|${storage.account}|$name",
                        ).toString()
                    } else {
                        directory.toURI().toString()
                    },
                    contentType = LocalLibraryContentType.COMICS,
                    bookshelfId = "shelf-$name",
                )
            }
            val organization = if (folderOnly) {
                LocalLibraryOrganizationMode.FOLDER
            } else {
                LocalLibraryOrganizationMode.INDIVIDUAL_FILES
            }
            val config = LocalLibraryConfig(
                setupCompleted = true,
                roots = roots,
                bookshelves = listOf(
                    LocalBookshelf(
                        "shelf-a",
                        "Shelf A",
                        LocalLibraryContentType.COMICS,
                        organization,
                    ),
                    LocalBookshelf(
                        "shelf-b",
                        "Shelf B",
                        LocalLibraryContentType.COMICS,
                        organization,
                    ),
                ),
            )
            if (folderOnly || detailsOnly) {
                profiles.commitConfiguration(profile.id) { draft ->
                    LocalLibraryPreferences(profile.id, Json, draft).setConfig(config)
                }
            } else {
                local.setConfig(config)
            }
            val titles = listOf("UI download A", "UI download B", "UI download C")
            val absentTitle = "UI download Z - unavailable"
            val allTitles = titles + absentTitle
            val seed = archiveBytes()
            val items = allTitles.mapIndexed { index, title ->
                val root = roots[if (!folderOnly && index == 1) 1 else 0]
                val path = if (folderOnly) "Collection/$title.cbz" else "$title.cbz"
                LocalLibraryItem(
                    itemKey = LocalLibraryLocator.itemKey(root.id, path),
                    rootId = root.id,
                    relativePath = path,
                    contentType = LocalLibraryContentType.COMICS,
                    kind = LocalLibraryItem.Kind.FILE_ENTRY,
                    format = "cbz",
                    sizeBytes = seed.size.toLong(),
                    modifiedAt = listOf(100L, 300L, 200L, 400L)[index],
                    missing = !network && index == 3,
                )
            }
            // The real screen consumes a successful persistent index without scanning a server.
            val indexed = LocalLibraryIndex(
                scannedAt = System.currentTimeMillis() - if (lifecycleOnly) 7 * 24 * 60 * 60 * 1000L else 0,
                items = items + if (folderOnly) {
                    listOf(
                        LocalLibraryItem(
                            itemKey = LocalLibraryLocator.itemKey(roots[0].id, "Collection"),
                            rootId = roots[0].id,
                            relativePath = "Collection",
                            contentType = LocalLibraryContentType.COMICS,
                            kind = LocalLibraryItem.Kind.FOLDER,
                            format = "directory",
                            sizeBytes = 0,
                            modifiedAt = 1,
                        ),
                    )
                } else {
                    emptyList()
                },
            )
            local.setIndex(indexed)
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is LocalFolderSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            }
            source = active
            assertEquals(network, active.supportsFileTransfers)
            check(provider.findOwnedSourceDirs(active).isEmpty())
            // B, A, C insertion order makes added sorting distinct from title and modified time.
            for (index in listOf(1, 0, 2, 3)) {
                val item = items[index]
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = LocalLibraryLocator.entryUrl(profile.id, item.rootId, item.relativePath),
                            title = allTitles[index],
                            initialized = true,
                        ),
                    ),
                ).single()
                stored += manga
                if (folderOnly || detailsOnly) {
                    Injekt.get<ChapterRepository>().addAll(
                        listOf(Chapter.create().copy(mangaId = manga.id, url = manga.url, name = manga.title)),
                    )
                }
                if (index == 3 || lifecycleOnly) {
                    assertEquals(0, downloads.getDownloadCount(manga))
                    continue
                }
                if (network) {
                    check(provider.findMangaDirs(manga.title, active).isEmpty())
                    val directory = provider.getMangaDir(manga.title, active).getOrThrow()
                    check(directory.parentFile?.name == active.downloadDirectoryName())
                    val fileName = if (folderOnly || detailsOnly) {
                        provider.getChapterDirName(manga.title, null, manga.url) + ".cbz"
                    } else {
                        "fixture.cbz"
                    }
                    val file = checkNotNull(directory.createFile(fileName))
                    file.openOutputStream().use { it.write(seed) }
                    files[manga.id] = file
                    cache.addChapter(fileName, directory, manga)
                    assertEquals(1, downloads.getDownloadCount(manga))
                } else {
                    val file = File(File(nativeRoot, if (index == 1) "b" else "a"), item.relativePath)
                    check(!file.exists())
                    file.writeBytes(seed)
                    nativeFiles += file
                    assertEquals(0, downloads.getDownloadCount(manga))
                }
            }
            stored.sortBy { titles.indexOf(it.title).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            if (folderOnly) {
                mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = LocalLibraryLocator.entryUrl(profile.id, roots[0].id, "Collection"),
                            title = "Collection",
                            initialized = true,
                        ),
                    ),
                )
            }
            assertTrue(!active.needsInitialScan())
            if (folderOnly) {
                val savedConfig = local.getConfig()
                assertEquals(2, savedConfig.roots.size)
                assertEquals(
                    LocalLibraryOrganizationMode.FOLDER,
                    savedConfig.organizationMode(savedConfig.roots.first()),
                )
                val children = active.browseIndexedLibrary(
                    mangas = mangas.getMangaBySourceId(profile.id),
                    query = "",
                    scope = LibraryContentScope.ALL,
                    parentUrl = LocalLibraryLocator.entryUrl(profile.id, roots[0].id, "Collection"),
                )
                assertEquals(
                    "Actual folder browse must expose all indexed children",
                    allTitles.toSet(),
                    children.map {
                        it.title
                    }.toSet(),
                )
            }
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(
                                LocalLibraryScreen(
                                    profile.id,
                                    LibraryContentScope.ALL,
                                    null,
                                    false,
                                    parentUrl = if (folderOnly) {
                                        LocalLibraryLocator.entryUrl(profile.id, roots[0].id, "Collection")
                                    } else {
                                        null
                                    },
                                ),
                            ) {
                                navigator = it
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                if (detailsOnly) {
                    awaitTitles(if (network) allTitles else titles, allTitles)
                    val expected = stored.first()
                    val baseline = mangas.getMangaById(expected.id)
                    val units = Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id)
                    check(units.single().url == expected.url)
                    click(awaitLabel(expected.title))
                    withTimeout(15_000) {
                        while (navigator?.lastItem !is MangaScreen) delay(50)
                    }
                    val screen = navigator!!.lastItem as MangaScreen
                    check(
                        screen.mangaId == expected.id && screen.sourceId == profile.id &&
                            screen.mangaUrl == expected.url,
                    )
                    awaitLabel(context.stringResource(MR.strings.unknown_author))
                    awaitLabel(context.stringResource(MR.strings.description_placeholder), scroll = true)
                    awaitLabel(context.pluralStringResource(MR.plurals.manga_num_chapters, 1, 1), scroll = true)
                    awaitLabel(units.single().name, scroll = true)
                    val current = mangas.getMangaById(expected.id)
                    check(current.source == baseline.source && current.url == baseline.url)
                    check(current.author.isNullOrBlank() && current.artist.isNullOrBlank())
                    check(current.description.isNullOrBlank())
                    check(Injekt.get<ChapterRepository>().getChapterByMangaId(expected.id) == units)
                    if (network) {
                        withTimeout(10_000) {
                            while (server.requests.get() != 1 || server.unexpectedRequests.get() != 0) delay(25)
                        }
                    }
                    scenario.onActivity { check(navigator!!.pop()) }
                    awaitTitles(if (network) allTitles else titles, allTitles)
                    check(mangas.getMangaBySourceId(profile.id).size == stored.size)
                    for (file in nativeFiles) check(file.readBytes().contentEquals(seed))
                    for (manga in stored.filter { it.id in files }) {
                        check(files.getValue(manga.id).openInputStream().use { it.readBytes() }.contentEquals(seed))
                    }
                    assertEquals(if (network) 1 else 0, server.requests.get())
                    assertEquals(0, server.unexpectedRequests.get())
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "local_standard_details",
                                "$mode;single-file;canonical-owner;empty-metadata;returned",
                            )
                            putInt("details_storage_requests", server.requests.get())
                        },
                    )
                    return@use
                }
                if (folderOnly) {
                    val complete = context.stringResource(MR.strings.shelf_download_complete, 1L)
                    suspend fun verify(count: Int, stage: String) {
                        var lastShown = emptyList<String>()
                        var lastCount = -1
                        try {
                            withTimeout(15_000) {
                                while (true) {
                                    val tree = nodes(instrumentation.uiAutomation.rootInActiveWindow).toList()
                                    val visible = tree.filter { it.isVisibleToUser }
                                    val shown = visible.mapNotNull { it.text?.toString() }
                                    val indicators = visible.count { it.contentDescription?.toString() == complete }
                                    lastShown = shown
                                    lastCount = indicators
                                    if (titles.all { it in shown } && indicators == count) break
                                    if (titles.any { it !in shown }) {
                                        visible.firstOrNull { it.isScrollable }
                                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                    }
                                    delay(100)
                                }
                            }
                        } catch (error: TimeoutCancellationException) {
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString("folder_download_failure", "$stage;count=$lastCount;shown=$lastShown")
                                },
                            )
                            throw error
                        }
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("folder_download_state", "mode=$mode;stage=$stage;count=$count")
                            },
                        )
                    }
                    verify(3, "list")
                    library.showChapterReadProgress.set(true)
                    verify(3, "list-progress-on")
                    library.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE)
                    verify(3, "grid")
                    library.showChapterReadProgress.set(false)
                    verify(3, "grid-progress-off")
                    downloads.deleteManga(stored[0], active, removeQueued = false)
                    verify(2, "grid-removed-one")
                    library.chapterCoverDisplayMode.set(Manga.CHAPTER_COVER_DISPLAY_TEXT)
                    verify(2, "list-removed-one")
                    for (manga in stored.take(3).drop(1)) {
                        assertTrue(
                            files.getValue(manga.id).openInputStream().use {
                                it.readBytes()
                            }.contentEquals(seed),
                        )
                        downloads.deleteManga(manga, active, removeQueued = false)
                    }
                    verify(0, "list-removed-all")
                    assertEquals(0, server.requests.get())
                    instrumentation.sendStatus(0, Bundle().apply { putInt("folder_requests", server.requests.get()) })
                    return@use
                }
                awaitTitles(if (network) allTitles else titles, allTitles)
                if (lifecycleOnly) {
                    val owner = storage.account
                    suspend fun verify(phase: String) {
                        awaitTitles(allTitles, allTitles)
                        delay(2_500)
                        assertEquals("$mode $phase must not contact storage", 0, server.requests.get())
                        assertEquals(indexed, local.getIndex())
                        assertEquals(owner, NetworkStoragePreferences(profile.id).account)
                        assertTrue(stored.all { downloads.getDownloadCount(it) == 0 })
                        instrumentation.sendStatus(
                            2,
                            Bundle().apply { putString("shelf_cache", "$mode;$phase;directory=0;connections=0") },
                        )
                    }
                    verify("warm-week-old-index")
                    profiles.commitConfiguration(profile.id) { }
                    val replaced = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
                    assertTrue(replaced !== active)
                    source = replaced
                    assertTrue(!replaced.needsInitialScan())
                    verify("source-recreated")
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    verify("foreground-resume")
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme {
                                Navigator(LocalLibraryScreen(profile.id, LibraryContentScope.ALL, null, false))
                            }
                        }
                    }
                    verify("activity-recreated")
                    return@use
                }
                click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                click(
                    awaitLabel(
                        context.stringResource(
                            if (network) MR.strings.label_downloaded else MR.strings.local_library_available,
                        ),
                    ),
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitTitles(titles, allTitles)
                click(awaitLabel("Shelf A"))
                awaitTitles(listOf(titles[0], titles[2]), allTitles)
                click(awaitLabel("Shelf B"))
                awaitTitles(listOf(titles[1]), allTitles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(titles, allTitles)
                suspend fun selectSort(label: String) {
                    click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                    click(awaitLabel(context.stringResource(MR.strings.action_sort)))
                    click(awaitLabel(label))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
                selectSort(context.stringResource(MR.strings.action_sort_alpha))
                awaitTitles(titles.reversed(), allTitles)
                selectSort(context.stringResource(MR.strings.local_library_sort_added))
                awaitTitles(listOf(titles[2], titles[0], titles[1]), allTitles)
                selectSort(context.stringResource(MR.strings.local_library_sort_modified))
                awaitTitles(listOf(titles[1], titles[2], titles[0]), allTitles)
                selectSort(context.stringResource(MR.strings.action_sort_alpha))
                awaitTitles(titles.reversed(), allTitles)
                click(awaitLabel("Shelf A"))
                awaitTitles(listOf(titles[2], titles[0]), allTitles)
                if (network) {
                    // Exercise the production deletion event while the actual shelf remains mounted.
                    downloads.deleteManga(stored[2], active, removeQueued = false)
                    awaitTitles(listOf(titles[0]), allTitles)
                    assertTrue(!files.getValue(stored[2].id).exists())
                    assertEquals(0, downloads.getDownloadCount(stored[2]))
                    click(awaitLabel("Shelf B"))
                    awaitTitles(listOf(titles[1]), allTitles)
                    click(awaitLabel(context.stringResource(MR.strings.all)))
                    awaitTitles(listOf(titles[1], titles[0]), allTitles)
                }
                for (file in nativeFiles) assertTrue(file.readBytes().contentEquals(seed))
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
                            "mode=$mode;shelves=a,b,all;sort=title,added,modified;available-or-downloaded=verified;manual-delete=$network",
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
                    check(nativeRoot.parentFile == context.cacheDir.canonicalFile)
                    check(nativeRoot.deleteRecursively())
                    assertTrue(connections.getProfiles().none { it.id == profile.id })
                    assertTrue("Retired IDs must remain reserved", connections.isKnownConnectionId(profile.id))
                    assertTrue(mangas.getMangaBySourceId(profile.id).isEmpty())
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("downloaded_ui_cleanup", "owned-profile-files-sql-removed")
                            putString("storage_mode", mode.name)
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

    private class UnexpectedRequestServer(private val detailsMode: LibraryStorageMode? = null) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val unexpectedRequests = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            unexpectedRequests.incrementAndGet()
                            it.soTimeout = 5_000
                            val expectedRead = when (detailsMode) {
                                LibraryStorageMode.WEBDAV -> {
                                    val line = it.getInputStream().bufferedReader().readLine().orEmpty()
                                    InstrumentationRegistry.getInstrumentation().sendStatus(
                                        0,
                                        Bundle().apply { putString("details_storage_probe", line) },
                                    )
                                    line == "PROPFIND /root/.koharia/storage-id HTTP/1.1"
                                }
                                LibraryStorageMode.SMB -> {
                                    val input = DataInputStream(it.getInputStream())
                                    val transport = ByteArray(4).also(input::readFully)
                                    val header = ByteArray(64).also(input::readFully)
                                    val command = (header[12].toInt() and 255) or
                                        ((header[13].toInt() and 255) shl 8)
                                    InstrumentationRegistry.getInstrumentation().sendStatus(
                                        0,
                                        Bundle().apply { putInt("details_smb_command", command) },
                                    )
                                    transport[0] == 0.toByte() &&
                                        header.take(
                                            4,
                                        ) == listOf(0xfe.toByte(), 83.toByte(), 77.toByte(), 66.toByte()) &&
                                        command == 0
                                }
                                else -> false
                            }
                            if (expectedRead) unexpectedRequests.decrementAndGet()
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
