package koharia.connection

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.DocumentsContract
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
import androidx.paging.PagingSource
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.komga.api.dto.KomgaOfflineFilterMetadata
import koharia.komga.api.dto.withOfflineFilterMetadata
import koharia.komga.ui.organization.KomgaSeriesOrganizationScreen
import koharia.source.komga.KomgaSource
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryIndex
import koharia.source.local.LocalLibraryItem
import koharia.source.local.LocalLibraryLocator
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.storage.LibraryStorageBackend
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageBlockCache
import koharia.storage.StorageCapabilities
import koharia.storage.StorageEntry
import koharia.storage.StorageSession
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.source.BaseSourcePagingSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShelfDownloadPresentationDeviceTest {
    @Test
    fun actualSharedLayoutsKeepDownloadStatesIndependentOfReadingProgress(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val historyDetailsRoute = InstrumentationRegistry.getArguments().getString("historyDetailsRoute") == "true"
        val historyRoute = historyDetailsRoute ||
            InstrumentationRegistry.getArguments().getString("historyDownloadRoute") == "true"
        val storageMode = InstrumentationRegistry.getArguments().getString("historyStorageMode")
            ?.let(LibraryStorageMode::valueOf)
        val cacheBoundary = InstrumentationRegistry.getArguments().getString("networkContentCacheBoundary") == "true"
        check(storageMode == null || (historyRoute && storageMode != LibraryStorageMode.LOCAL))
        check(!cacheBoundary || storageMode != null)
        val remoteRefresh = InstrumentationRegistry.getArguments().getString("favoriteRemoteCountRefresh") == "true"
        check(!remoteRefresh || (historyRoute && storageMode == null))
        val rejection = InstrumentationRegistry.getArguments().getString("seriesActionsRejection").orEmpty()
        check(rejection in setOf("", "permission", "unavailable"))
        val seriesActionsRoute = rejection.isNotEmpty() ||
            InstrumentationRegistry.getArguments().getString("seriesActionsRoute") == "true"
        val detailsRoute = historyDetailsRoute || seriesActionsRoute ||
            InstrumentationRegistry.getArguments().getString("detailsDownloadRoute") == "true"
        check(
            !detailsRoute ||
                ((!historyRoute || historyDetailsRoute) && storageMode == null && !remoteRefresh && !cacheBoundary),
        )
        check(!historyDetailsRoute || !seriesActionsRoute)
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
        val profile = profiles.add(
            if (storageMode == null) "komga" else "local-folder",
            "Download states ${UUID.randomUUID()}",
        )
        val actionServer = rejection.takeIf { it.isNotEmpty() }?.let { KomgaActionsFixtureServer() }
        var source: Source? = null
        var stored: Manga? = null
        var ownedCacheRoot: File? = null
        try {
            replace(libraryPreferences.showLibraryReadProgress, false)
            replace(libraryPreferences.showShelfDownloadCount, true)
            if (detailsRoute) {
                replace(libraryPreferences.chapterCoverDisplayMode, Manga.CHAPTER_COVER_DISPLAY_TEXT)
                replace(Injekt.get<SharedAppPreferences>().basePreferences().downloadedOnly, false)
            }
            val local = storageMode?.let { LocalLibraryPreferences(profile.id, Json) }
            fun updateIndex(count: Int) {
                checkNotNull(local).setIndex(
                    LocalLibraryIndex(
                        scannedAt = System.currentTimeMillis(),
                        items = (0..count).map { number ->
                            val path = if (number == 0) "owned" else "owned/Unit $number.cbz"
                            LocalLibraryItem(
                                itemKey = LocalLibraryLocator.itemKey("root", path),
                                rootId = "root",
                                relativePath = path,
                                kind = if (number == 0) LocalLibraryItem.Kind.SERIES else LocalLibraryItem.Kind.CHAPTER,
                                format = if (number == 0) "directory" else "cbz",
                                sizeBytes = 0,
                                modifiedAt = 1,
                            )
                        },
                    ),
                )
            }
            if (storageMode == null) {
                val preferences = sourcePreferences("source_${profile.id}")
                check(preferences.all.isEmpty())
                check(preferences.edit().putString("Address", actionServer?.address ?: "http://127.0.0.1:9").commit())
            } else {
                val network = NetworkStoragePreferences(profile.id)
                network.save(
                    NetworkStorageConfiguration(
                        mode = storageMode,
                        address = if (storageMode == LibraryStorageMode.SMB) {
                            "smb://127.0.0.1:9/share"
                        } else {
                            "http://127.0.0.1:9/root"
                        },
                        rootIdentity = "fixture-${profile.id}",
                    ),
                    "fixture",
                    "fixture",
                )
                checkNotNull(local).setConfig(
                    LocalLibraryConfig(
                        setupCompleted = true,
                        roots = listOf(
                            LocalLibraryRootConfig(
                                id = "root",
                                treeUri = DocumentsContract.buildDocumentUri(
                                    NetworkStorageRuntime.authority(context),
                                    "${profile.id}|${network.account}|root",
                                ).toString(),
                            ),
                        ),
                    ),
                )
                updateIndex(2)
            }
            val active = withTimeout(10_000) {
                while (true) {
                    val candidate = Injekt.get<SourceManager>().get(profile.id)
                    if ((storageMode == null && candidate is KomgaSource) ||
                        (storageMode != null && candidate is LocalFolderSource)
                    ) {
                        break
                    }
                    delay(25)
                }
                checkNotNull(Injekt.get<SourceManager>().get(profile.id))
            }
            source = active
            check(!(active as ConnectionDownloadStorageAdapter).usesSharedDownloadStorage)
            check(provider.findOwnedSourceDirs(active).isEmpty())
            val mangaUrl = if (local == null) {
                "/series/owned"
            } else {
                LocalLibraryLocator.entryUrl(profile.id, "root", "owned")
            }
            val manga = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = profile.id,
                        url = mangaUrl,
                        title = "Owned download states",
                        favorite = remoteRefresh || detailsRoute,
                        initialized = detailsRoute,
                        lastUpdate = if (historyDetailsRoute) System.currentTimeMillis() else 0,
                        notes = if (remoteRefresh) "Retained fixture note" else "",
                        viewerFlags = if (remoteRefresh) 123L else 0L,
                        memo = buildJsonObject {
                            if (remoteRefresh) {
                                put("booksCount", 2)
                                put("fixtureLocalMetadata", "keep")
                            }
                        }.let {
                            if (historyDetailsRoute) it.withOfflineFilterMetadata(KomgaOfflineFilterMetadata()) else it
                        },
                    ),
                ),
            ).single()
                .let {
                    if (remoteRefresh) {
                        check(mangas.update(MangaUpdate(id = it.id, notes = "Retained fixture note")))
                        mangas.getMangaById(it.id)
                    } else {
                        it
                    }
                }
            stored = manga
            val units = chapters.addAll(
                (1..2).map { Chapter.create().copy(mangaId = manga.id, url = "/book/$it", name = "Unit $it") },
            )
            val historyRows = if (historyRoute) {
                val repository = Injekt.get<HistoryRepository>()
                repository.upsertHistory(HistoryUpdate(units[0].id, Date(), 0))
                val row = repository.getHistory("", profile.id).first().single()
                check(row.mangaUrl == manga.url && row.coverData.sourceId == active.id && row.mangaId == manga.id)
                listOf(HistoryUiModel.Header(LocalDate.now()), HistoryUiModel.Item(row))
            } else {
                emptyList()
            }
            val seed = archive()
            val contentEntry = StorageEntry("owned/Unit 1.cbz", false, seed.size.toLong(), version = "fixture-v1")
            val cacheContext = if (cacheBoundary) {
                val owned = File(context.cacheDir, "network-download-boundary-${UUID.randomUUID()}").canonicalFile
                check(owned.parentFile == context.cacheDir.canonicalFile && !owned.exists() && owned.mkdir())
                ownedCacheRoot = owned
                object : ContextWrapper(context) {
                    override fun getFilesDir(): File = owned
                }
            } else {
                null
            }
            val contentCache = cacheContext?.let {
                val network = NetworkStoragePreferences(profile.id)
                StorageBlockCache(
                    File(it.filesDir, "library-storage-cache"),
                    StorageSession(profile.id, network.account, network.configuration.rootIdentity) { true },
                    ArchiveBackend(contentEntry, seed),
                ) { 2L * 1024 * 1024 }
            }
            var cachedArchive = contentCache?.prepareComplete(contentEntry)
            if (cachedArchive != null) {
                check(cachedArchive.readBytes().contentEquals(seed))
                check(downloads.getDownloadCount(manga) == 0)
            }
            var total by mutableStateOf<Long?>(2L)
            var mode by mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.List)
            suspend fun updateTotal(value: Long?) {
                withContext(Dispatchers.Main) { total = value }
                if (remoteRefresh) {
                    val page = object : BaseSourcePagingSource(active as KomgaSource) {
                        override suspend fun requestNextPage(currentPage: Int) = MangasPage(
                            listOf(
                                SManga.create().apply {
                                    url = manga.url
                                    title = "Remote fixture title"
                                    memo = buildJsonObject { put("booksCount", value ?: 0L) }
                                },
                            ),
                            hasNextPage = false,
                        )
                    }.load(PagingSource.LoadParams.Refresh(null, 20, false))
                    check(page is PagingSource.LoadResult.Page && page.data.single().id == manga.id)
                    val retained = mangas.getMangaById(manga.id)
                    check(retained.favorite && retained.title == manga.title)
                    check(retained.notes == manga.notes && retained.viewerFlags == manga.viewerFlags)
                    check(retained.memo["fixtureLocalMetadata"] == manga.memo["fixtureLocalMetadata"])
                    check(chapters.getChapterByMangaId(manga.id).size == 2)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "favorite_remote_count",
                                "remote=$value;stored=${retained.memo["booksCount"]};" +
                                    "page=${page.data.single().memo["booksCount"]};userFields=retained",
                            )
                        },
                    )
                } else if (local != null) {
                    updateIndex(value?.toInt() ?: 0)
                    check(chapters.getChapterByMangaId(manga.id).size == 2)
                    check(mangas.getMangaById(manga.id).memo.isEmpty())
                } else if (historyRoute) {
                    check(
                        mangas.update(
                            MangaUpdate(
                                manga.id,
                                memo = buildJsonObject {
                                    value?.let { put("booksCount", it) }
                                },
                            ),
                        ),
                    )
                }
            }
            val none = listOf(
                context.stringResource(MR.strings.shelf_download_partial, 1L, 2L),
                context.stringResource(MR.strings.shelf_download_complete, 2L),
                context.stringResource(MR.strings.shelf_download_unknown, 2L),
                context.stringResource(MR.strings.shelf_download_partial, 2L, 3L),
            )
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
            if (detailsRoute) {
                val baseline = mangas.getMangaById(manga.id)
                val downloadLabel = context.stringResource(
                    if ((active as ConnectionMangaBehaviorAdapter).mangaBehavior.usesCacheTerminology) {
                        MR.strings.komga_action_cache
                    } else {
                        MR.strings.manga_download
                    },
                )
                fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                    if (root == null) return emptyList()
                    return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
                }
                suspend fun verify(expected: Set<Long>, stage: String) {
                    var lastLabels = emptyList<String>()
                    var lastRows = emptyList<String>()
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                val root = instrumentation.uiAutomation.rootInActiveWindow
                                val visible = labels(root)
                                lastLabels = visible
                                if (units.all { it.name in visible }) {
                                    val rows = mutableListOf<String>()
                                    val states = units.map { unit ->
                                        var row = nodes(root).first { it.text?.toString() == unit.name }
                                        while (true) {
                                            val parent = row.parent ?: break
                                            val content = labels(parent)
                                            if (manga.title in content ||
                                                units.any { it.id != unit.id && it.name in content }
                                            ) {
                                                break
                                            }
                                            row = parent
                                        }
                                        rows += "${unit.name}:${labels(row)}"
                                        val canDownload = downloadLabel in labels(row)
                                        canDownload == (unit.id !in expected)
                                    }
                                    lastRows = rows
                                    if (states.all { it }) break
                                } else {
                                    nodes(root).firstOrNull { it.isScrollable && it.isVisibleToUser }
                                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                }
                                delay(100)
                            }
                        }
                    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("details_download_failure", "$stage;labels=$lastLabels;rows=$lastRows")
                            },
                        )
                        throw error
                    }
                    check(downloads.getDownloadCount(manga) == expected.size)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putString("details_download_state", "$stage;downloaded=${expected.size}") },
                    )
                }
                var navigator: Navigator? = null
                if (historyDetailsRoute) connections.activeConnectionId.set(profile.id)
                FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme {
                                Navigator(if (historyDetailsRoute) HistoryTab else MangaScreen(manga.id)) { host ->
                                    navigator = host
                                    cafe.adriel.voyager.navigator.CurrentScreen()
                                }
                            }
                        }
                    }
                    if (historyDetailsRoute) {
                        add(units[1])
                        var historyRow: AccessibilityNodeInfo? = null
                        withTimeout(15_000) {
                            while (historyRow == null) {
                                historyRow = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                                    .firstOrNull { it.isVisibleToUser && it.text?.toString() == manga.title }
                                if (historyRow == null) delay(50)
                            }
                        }
                        var row = checkNotNull(historyRow)
                        while (!row.isClickable) row = checkNotNull(row.parent)
                        val cover = nodes(row).single {
                            it.isClickable && it.isEnabled && it.isVisibleToUser && labels(it).all(String::isBlank)
                        }
                        check(cover.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        withTimeout(15_000) { while (navigator?.lastItem !is MangaScreen) delay(50) }
                        check((navigator!!.lastItem as MangaScreen).mangaId == manga.id)
                        verify(setOf(units[1].id), "history-cover-details")
                        check(mangas.getMangaBySourceId(profile.id).single() == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        val backLabel = context.stringResource(MR.strings.action_bar_up_description)
                        var back = nodes(instrumentation.uiAutomation.rootInActiveWindow).first {
                            it.isVisibleToUser && it.contentDescription?.toString() == backLabel
                        }
                        while (!back.isClickable) back = checkNotNull(back.parent)
                        check(back.isEnabled && back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        withTimeout(15_000) {
                            while (navigator?.lastItem !== HistoryTab ||
                                manga.title !in labels(instrumentation.uiAutomation.rootInActiveWindow)
                            ) {
                                delay(50)
                            }
                        }
                        check(mangas.getMangaBySourceId(profile.id).single() == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        val history = Injekt.get<HistoryRepository>().getHistory("", profile.id).first().single()
                        check(history == (historyRows.last() as HistoryUiModel.Item).item)
                        remove(units[1])
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "history_details_route",
                                    "HistoryTab;cover;MangaScreen;canonical-owner;SQL-history-files-retained;returned",
                                )
                            },
                        )
                        return@use
                    }
                    if (seriesActionsRoute) {
                        add(units[0])
                        verify(setOf(units[0].id), "actions-retained-download")
                        if (actionServer != null) {
                            (active as KomgaSource).organizationRepository().authorize()
                            check(active.organizationRepository().cachedRoles() == listOf("ADMIN"))
                            actionServer.rejection = rejection
                        }
                        verifySeriesActionsNavigation({ navigator }, "Komga", onDestination = {
                            if (actionServer != null) {
                                withTimeout(15_000) { while (!actionServer.waiting.get()) delay(50) }
                                val actions = listOf(
                                    context.stringResource(MR.strings.komga_add_collection),
                                    context.stringResource(MR.strings.komga_add_readlist),
                                )
                                check(labels(instrumentation.uiAutomation.rootInActiveWindow).none { it in actions })
                                instrumentation.sendStatus(
                                    0,
                                    Bundle().apply {
                                        putString("komga_actions_loading", "account-pending;actions-absent")
                                    },
                                )
                                actionServer.release.countDown()
                                val error = if (rejection == "permission") {
                                    context.stringResource(MR.strings.komga_organization_permission)
                                } else {
                                    context.stringResource(MR.strings.exception_http, 503)
                                }
                                withTimeout(15_000) {
                                    while (error !in labels(instrumentation.uiAutomation.rootInActiveWindow)) delay(50)
                                }
                                val root = instrumentation.uiAutomation.rootInActiveWindow
                                actions.forEach { label ->
                                    var button = nodes(root).first { it.text?.toString() == label }
                                    while (!button.isClickable) button = checkNotNull(button.parent)
                                    check(!button.isEnabled)
                                }
                                check(actionServer.writes.get() == 0 && actionServer.unhandled.isEmpty()) {
                                    "Unhandled fixture routes: ${actionServer.unhandled}"
                                }
                                instrumentation.sendStatus(
                                    0,
                                    Bundle().apply {
                                        putString(
                                            "komga_actions_rejection",
                                            "$rejection;error-visible;buttons-disabled;writes=0",
                                        )
                                    },
                                )
                            }
                        }) {
                            it is KomgaSeriesOrganizationScreen && it.sourceId == profile.id &&
                                it.resourceUrls == listOf(manga.url)
                        }
                        check(mangas.getMangaById(manga.id) == baseline)
                        check(
                            chapters.getChapterByMangaId(manga.id).associateBy {
                                it.id
                            } == units.associateBy { it.id },
                        )
                        if (actionServer != null) {
                            verify(setOf(units[0].id), "rejection-retained-download")
                            val loader = DownloadPageLoader(ReaderChapter(units[0]), manga, active, downloads, provider)
                            try {
                                val pages = loader.getPages()
                                check(pages.size == 1)
                                checkNotNull(pages.single().stream).invoke().use {
                                    val bitmap = checkNotNull(BitmapFactory.decodeStream(it))
                                    check(bitmap.width == 32 && bitmap.height == 48)
                                    bitmap.recycle()
                                }
                            } finally {
                                loader.recycle()
                            }
                            check(actionServer.writes.get() == 0 && actionServer.unhandled.isEmpty()) {
                                "Unhandled fixture routes: ${actionServer.unhandled}"
                            }
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString(
                                        "komga_actions_local_file",
                                        "$rejection;row-visible;DownloadPageLoader;PNG-decoded;" +
                                            "accountReads=${actionServer.accountReads.get()};otherReads=${actionServer.otherReads.get()}",
                                    )
                                },
                            )
                        }
                        remove(units[0])
                        return@use
                    }
                    verify(emptySet(), "none")
                    add(units[0])
                    verify(setOf(units[0].id), "partial")
                    add(units[1])
                    verify(units.map { it.id }.toSet(), "complete")
                    remove(units[0])
                    verify(setOf(units[1].id), "removed-one")
                    remove(units[1])
                    verify(emptySet(), "removed-all")
                    check(mangas.getMangaById(manga.id) == baseline)
                    check(chapters.getChapterByMangaId(manga.id).associateBy { it.id } == units.associateBy { it.id })
                }
                return@runBlocking
            }
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            if (historyRoute) {
                                HistoryScreen(
                                    state = HistoryScreenModel.State(list = historyRows),
                                    snackbarHostState = remember { SnackbarHostState() },
                                    onSearchQueryChange = {},
                                    onRefresh = {},
                                    onClickCover = {},
                                    onClickResume = { _, _ -> },
                                    onDialogChange = {},
                                )
                            } else {
                                val items = remember {
                                    flowOf(PagingData.from(listOf(MutableStateFlow(manga) as StateFlow<Manga>)))
                                }.collectAsLazyPagingItems()
                                BrowseSourceContent(
                                    source = active,
                                    mangaList = items,
                                    columns = GridCells.Fixed(2),
                                    displayMode = mode,
                                    snackbarHostState = remember { SnackbarHostState() },
                                    contentPadding = PaddingValues(16.dp),
                                    showLibraryBadges = false,
                                    readingUnitCount = { total },
                                    readProgress = { MangaReadProgress(1, total ?: 2) },
                                    onWebViewClick = {},
                                    onHelpClick = {},
                                    onMangaClick = {},
                                    onMangaLongClick = {},
                                )
                            }
                        }
                    }
                }
                suspend fun awaitDescription(expected: String?, stage: String) {
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                val labels = labels(instrumentation.uiAutomation.rootInActiveWindow)
                                if (manga.title in labels &&
                                    if (expected == null) none.none { it in labels } else expected in labels
                                ) {
                                    break
                                }
                                delay(50)
                            }
                        }
                    } catch (error: Exception) {
                        val actual = labels(instrumentation.uiAutomation.rootInActiveWindow)
                        throw AssertionError("$stage expected=$expected actual=$actual", error)
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "download_presentation",
                                "history=$historyRoute;storage=$storageMode;layout=$mode;stage=$stage",
                            )
                        },
                    )
                }
                val layouts = if (historyRoute) {
                    listOf(LibraryDisplayMode.List)
                } else {
                    listOf(LibraryDisplayMode.List, LibraryDisplayMode.CompactGrid, LibraryDisplayMode.ComfortableGrid)
                }
                for (layout in layouts) {
                    withContext(Dispatchers.Main) {
                        mode = layout
                    }
                    updateTotal(2L)
                    libraryPreferences.showLibraryReadProgress.set(false)
                    awaitDescription(null, "none")
                    if (cacheBoundary) awaitDescription(null, "complete-content-cache-is-not-download")
                    add(units[0])
                    awaitDescription(none[0], "partial-progress-off")
                    add(units[1])
                    awaitDescription(none[1], "complete-progress-off")
                    if (cacheBoundary) {
                        check(NetworkStorageRuntime.clearUnusedContentCache(checkNotNull(cacheContext)) > 0)
                        check(!checkNotNull(cachedArchive).exists())
                        check(downloads.getDownloadCount(manga) == 2)
                        for (unit in units) {
                            val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                            check(
                                checkNotNull(directory.findFile(name)).openInputStream().use {
                                    it.readBytes().contentEquals(seed)
                                },
                            )
                        }
                        awaitDescription(none[1], "clear-content-cache-keeps-manual-files")
                        cachedArchive = checkNotNull(contentCache).prepareComplete(contentEntry)
                        check(checkNotNull(cachedArchive).readBytes().contentEquals(seed))
                    }
                    libraryPreferences.showLibraryReadProgress.set(true)
                    awaitDescription(none[1], "complete-progress-on")
                    updateTotal(3L)
                    awaitDescription(none[3], "remote-added")
                    updateTotal(1L)
                    awaitDescription(none[2], "remote-removed-retained-local")
                    updateTotal(0L)
                    awaitDescription(none[2], "unknown-total")
                    updateTotal(2L)
                    remove(units[1])
                    awaitDescription(none[0], "removed-one")
                    remove(units[0])
                    awaitDescription(null, "removed-all")
                    check(downloads.getDownloadCount(manga) == 0)
                    if (cacheBoundary) {
                        check(checkNotNull(cachedArchive).readBytes().contentEquals(seed))
                        check(checkNotNull(contentCache).prepareComplete(contentEntry) == cachedArchive)
                        awaitDescription(null, "delete-manual-keeps-content-cache")
                    }
                }
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
                            putString("download_presentation_cleanup", "complete")
                        },
                    )
                } finally {
                    actionServer?.close()
                    restores.asReversed().forEach { it() }
                    if (hadPrevious) {
                        connections.activeConnectionId.set(
                            previous,
                        )
                    } else {
                        connections.activeConnectionId.delete()
                    }
                    ownedCacheRoot?.let {
                        check(it.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                        check(it.deleteRecursively() && !it.exists())
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply { putString("network_content_cache_cleanup", "complete") },
                        )
                    }
                }
            }
        }
    }

    private class ArchiveBackend(
        private val entry: StorageEntry,
        private val bytes: ByteArray,
    ) : LibraryStorageBackend {
        override val capabilities = StorageCapabilities(false, true, false, false)
        override suspend fun stat(path: String): StorageEntry {
            check(path == entry.path)
            return entry
        }
        override suspend fun list(path: String): List<StorageEntry> = error("Unexpected list")
        override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
            check(entry == this.entry)
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        override suspend fun createDirectory(path: String): Unit = error("Unexpected write")
        override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit =
            error("Unexpected write")
        override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected move")
        override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected delete")
        override fun close() = Unit
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
}
