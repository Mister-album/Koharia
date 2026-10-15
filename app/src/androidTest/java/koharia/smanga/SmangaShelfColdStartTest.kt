package koharia.smanga

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.StrictMode
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.smanga.SmangaRepository
import koharia.smanga.ui.SmangaLibraryScreen
import koharia.smanga.ui.SmangaLibraryScreenModel
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Exercises the actual Main-thread Paging consumer without preparing catalogue caches first. */
@RunWith(AndroidJUnit4::class)
class SmangaShelfColdStartTest {
    @Test
    fun coldPagerLoadsDelayedBodyAndNewModelUsesWarmCacheWithoutRequests(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager: ConnectionProfileManager = Injekt.get()
        val connections: ConnectionPreferences = Injekt.get()
        val downloadedOnly = Injekt.get<BasePreferences>().downloadedOnly
        val previousDownloadedOnly = downloadedOnly.get()
        val hadDownloadedOnly = downloadedOnly.isSet()
        val previousActive = connections.activeConnectionId.get()
        val hadActive = connections.activeConnectionId.isSet()
        var originalPolicy: StrictMode.ThreadPolicy? = null
        var source: SmangaSource? = null
        var connectionId: Long? = null
        val server = ShelfServer()
        try {
            originalPolicy = withContext(Dispatchers.Main) {
                StrictMode.getThreadPolicy().also {
                    StrictMode.setThreadPolicy(
                        StrictMode.ThreadPolicy.Builder(it).detectNetwork().penaltyDeathOnNetwork().build(),
                    )
                }
            }
            assertTrue("The fixture must require more than a buffered body read", server.mangaBodySize > 8192)
            downloadedOnly.set(false)
            val profile = manager.add(SmangaConnectionProvider.ID, "Smanga cold shelf fixture")
            connectionId = profile.id
            SmangaPreferences(profile.id).save(server.address, server.username, "fixture-password", server.accountId)
            source = withTimeout(10_000) {
                var registered: SmangaSource?
                do {
                    registered = Injekt.get<SourceManager>().get(profile.id) as? SmangaSource
                    if (registered == null) delay(25)
                } while (registered == null)
                registered
            }.also { it.reload() }
            val active = source
            val accountKey = active.session().accountKey
            assertEquals(null, Injekt.get<SmangaRepository>().cache(profile.id, accountKey, "media", "data"))
            assertEquals(0, server.requestCount.get())

            val cold = collectShelf(active, "Cold shelf") { model, differ, initial ->
                assertEquals("A fresh connection defaults to all authorized media", 0L, model.state.value.selectedMedia)
                assertEquals(listOf(BOOK_TITLE), initial.map { it.title })
                assertEquals(1, server.mediaRequests.get())
                assertEquals(
                    "Only the initial automatic page request may satisfy this assertion",
                    1,
                    server.mangaRequests.get(),
                )
                server.failManga.set(true)
                model.refresh()
                val failedRefresh = withTimeoutOrNull(10_000) {
                    while (model.state.value.error == null || model.state.value.refreshing) delay(25)
                    true
                } ?: false
                assertTrue(
                    "Refresh must report its failure: ${failureTypes(model.state.value.error)}",
                    failedRefresh,
                )
                assertEquals(2, server.mangaRequests.get())
                assertEquals(initial.map { it.url }, differ.snapshot().items.map { it.value.url })

                server.failManga.set(false)
                var searchLoad: LoadState? = null
                differ.addLoadStateListener { searchLoad = it.refresh }
                model.search("missing")
                val emptySearch = withTimeoutOrNull(10_000) {
                    while (
                        server.emptySearchResponses.get() == 0 || differ.itemCount != 0 ||
                        searchLoad !is LoadState.NotLoading
                    ) {
                        val error = (searchLoad as? LoadState.Error)?.error
                        if (error != null) throw AssertionError("Empty search failed: ${failureTypes(error)}")
                        delay(25)
                    }
                    true
                } ?: false
                assertTrue(
                    "Empty search must complete: ${failureTypes(model.state.value.error)}",
                    emptySearch,
                )
                assertEquals(1, server.emptySearchResponses.get())
                assertEquals(3, server.mangaRequests.get())
                assertEquals("missing", model.state.value.query)
                assertEquals(
                    "Successful empty search must clear the old refresh failure",
                    null,
                    model.state.value.error,
                )
                assertTrue(differ.snapshot().items.isEmpty())
            }
            val account = withContext(Dispatchers.Main) { active.session().api.validate() }
            assertEquals(server.accountId, account.id)
            assertEquals(1, server.opdsRequests.get())
            val requestsBeforeWarm = server.requestCount.get()

            active.session().api.close()
            val warm = collectShelf(active, "Warm shelf with a closed API")
            assertEquals(cold.map { it.url }, warm.map { it.url })
            assertEquals(listOf(BOOK_TITLE), warm.map { it.title })
            assertEquals(
                "A new model must render the warm shelf without contacting the server",
                requestsBeforeWarm,
                server.requestCount.get(),
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.close()
                    connectionId?.let { id ->
                        try {
                            manager.remove(id).getOrThrow()
                        } finally {
                            Injekt.get<MangaRepository>().deleteMangaBySourceId(id)
                            Injekt.get<SmangaRepository>().removeConnection(id)
                        }
                    }
                } finally {
                    try {
                        if (hadDownloadedOnly) downloadedOnly.set(previousDownloadedOnly) else downloadedOnly.delete()
                        if (hadActive) {
                            connections.activeConnectionId.set(
                                previousActive,
                            )
                        } else {
                            connections.activeConnectionId.delete()
                        }
                    } finally {
                        try {
                            server.close()
                        } finally {
                            originalPolicy?.let { saved ->
                                withContext(Dispatchers.Main) { StrictMode.setThreadPolicy(saved) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun actualWarmShelfSurvivesSourceReplacementAndForegroundWithoutDirectoryRequests(): Unit =
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val manager = Injekt.get<ConnectionProfileManager>()
            val sources = Injekt.get<SourceManager>()
            val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
            val downloaded = Injekt.get<BasePreferences>().downloadedOnly
            val previousSelected = selected.get()
            val hadSelected = selected.isSet()
            val previousDownloaded = downloaded.get()
            val hadDownloaded = downloaded.isSet()
            val profile = manager.add(SmangaConnectionProvider.ID, "Smanga lifecycle ${UUID.randomUUID()}")
            val server = ShelfServer()
            fun status(
                value: String,
            ) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
            try {
                downloaded.set(false)
                manager.commitConfiguration(profile.id) { draft ->
                    SmangaPreferences(profile.id, draft).save(
                        server.address,
                        server.username,
                        "fixture-password",
                        server.accountId,
                    )
                }
                selected.set(profile.id)
                var source = sources.get(profile.id) as SmangaSource
                FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    suspend fun awaitShelf() = withTimeout(15_000) {
                        while (!hasVisibleTitle(instrumentation.uiAutomation.rootInActiveWindow, BOOK_TITLE)) delay(50)
                    }
                    awaitShelf()
                    assertEquals(1, server.mediaRequests.get())
                    assertEquals(1, server.mangaRequests.get())
                    val repository = Injekt.get<SmangaRepository>()
                    val owner = source.session().accountKey
                    val mediaCache = checkNotNull(repository.cache(profile.id, owner, "media", "data"))
                    fun verifyRequests(phase: String) {
                        assertEquals("$phase media", 1, server.mediaRequests.get())
                        assertEquals("$phase manga", 1, server.mangaRequests.get())
                        assertEquals("$phase writes", 0, server.businessWrites.get())
                        val paths = server.requestPaths.toList()
                        assertTrue(
                            paths.toString(),
                            paths.all {
                                it in setOf(
                                    "/fixture/api/login",
                                    "/fixture/api/user/me",
                                    "/fixture/api/media",
                                    "/fixture/api/manga",
                                    "/fixture/api/opds/manga/4/cover",
                                )
                            },
                        )
                        status("$phase;directory=0;requests=" + paths.joinToString(","))
                    }
                    val previous = source
                    manager.commitConfiguration(profile.id) { }
                    source = sources.get(profile.id) as SmangaSource
                    assertTrue(source !== previous)
                    assertEquals(owner, source.session().accountKey)
                    awaitShelf()
                    delay(2_500)
                    assertEquals(mediaCache, repository.cache(profile.id, owner, "media", "data"))
                    verifyRequests("source-recreated")
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitShelf()
                    delay(2_500)
                    assertEquals(mediaCache, repository.cache(profile.id, owner, "media", "data"))
                    verifyRequests("foreground-resume")
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    awaitShelf()
                    delay(2_500)
                    verifyRequests("activity-recreated")
                }
            } finally {
                try {
                    manager.remove(profile.id).getOrThrow()
                    Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
                    Injekt.get<SmangaRepository>().removeConnection(profile.id)
                } finally {
                    if (hadSelected) selected.set(previousSelected) else selected.delete()
                    if (hadDownloaded) downloaded.set(previousDownloaded) else downloaded.delete()
                    server.close()
                    status("owned-profile-sql-server-removed;preferences-restored")
                }
            }
        }

    @Test
    fun ordinaryShelfRetriesInitialFailureAndRetainsSuccessfulEmptyCache(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<SmangaRepository>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val previousSelected = selected.get()
        val hadSelected = selected.isSet()
        val previousDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(SmangaConnectionProvider.ID, "Smanga empty retry ${UUID.randomUUID()}")
        val server = ShelfServer().apply { failManga.set(true) }
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
            while (true) {
                findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let {
                    return@withTimeout it
                }
                delay(50)
            }
            error("Unreachable")
        }
        suspend fun retry() {
            var node: AccessibilityNodeInfo? = awaitLabel(context.stringResource(MR.strings.action_retry))
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        val emptyLabel = context.stringResource(MR.strings.no_results_found)
        val failureLabel = context.stringResource(MR.strings.smanga_error_server)
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                SmangaPreferences(profile.id, draft).save(
                    server.address,
                    server.username,
                    "fixture-password",
                    server.accountId,
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as SmangaSource
            val owner = source.session().accountKey
            suspend fun shelfCache() = repository.cachedCatalogue(profile.id, owner)
                .filter { it.group.startsWith("shelf/") }.sortedBy { it.group + it.key }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(failureLabel)
                delay(750)
                val initialFailures = server.mangaRequests.get()
                assertTrue(initialFailures > 0)
                delay(750)
                assertEquals(
                    "Initial failure must settle before user retry",
                    initialFailures,
                    server.mangaRequests.get(),
                )
                assertTrue(shelfCache().isEmpty())
                status("initial-503-no-successful-shelf;requests=$initialFailures")
                server.emptyShelf.set(true)
                server.failManga.set(false)
                retry()
                awaitLabel(emptyLabel)
                assertEquals(initialFailures + 1, server.mangaRequests.get())
                val successfulEmpty = shelfCache()
                assertTrue(successfulEmpty.isNotEmpty())
                status("ui-retry-published-empty")
                val before = server.requestCount.get()
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as SmangaSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().accountKey)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                assertEquals(before, server.requestCount.get())
                assertEquals(successfulEmpty, shelfCache())
                status("new-source-activity-empty-cache;requests=0")
                server.failManga.set(true)
                retry()
                awaitLabel(failureLabel)
                assertEquals(initialFailures + 2, server.mangaRequests.get())
                assertEquals(successfulEmpty, shelfCache())
                status("ui-refresh-failure-retains-complete-empty-shelf")
                server.failManga.set(false)
                server.emptyShelf.set(false)
                retry()
                awaitLabel(BOOK_TITLE)
                assertEquals(initialFailures + 3, server.mangaRequests.get())
                assertTrue(successfulEmpty != shelfCache())
                assertEquals(0, server.businessWrites.get())
                assertTrue(
                    server.requestPaths.all {
                        it in setOf(
                            "/fixture/api/login",
                            "/fixture/api/user/me",
                            "/fixture/api/media",
                            "/fixture/api/manga",
                            "/fixture/api/opds/manga/4/cover",
                        )
                    },
                )
                status("ui-retry-published-full;business-writes=0;unknown=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
                repository.removeConnection(profile.id)
            } finally {
                if (hadSelected) selected.set(previousSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(previousDownloaded) else downloaded.delete()
                server.close()
                status("owned-profile-sql-server-removed;preferences-restored")
            }
        }
    }

    @Test
    fun ordinaryRefreshSchedulesOnlyOwnedChaptersAndFailureRetainsCompleteCatalogue(): Unit =
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val manager = Injekt.get<ConnectionProfileManager>()
            val sources = Injekt.get<SourceManager>()
            val repository = Injekt.get<SmangaRepository>()
            val mangas = Injekt.get<MangaRepository>()
            val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
            val wm = androidx.work.WorkManager.getInstance(context)
            val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
            val downloaded = Injekt.get<BasePreferences>().downloadedOnly
            val autoDownload = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters
            val oldSelected = selected.get()
            val hadSelected = selected.isSet()
            val oldDownloaded = downloaded.get()
            val hadDownloaded = downloaded.isSet()
            val oldAutoDownload = autoDownload.get()
            val hadAutoDownload = autoDownload.isSet()
            val profile = manager.add(SmangaConnectionProvider.ID, "Smanga refresh ${UUID.randomUUID()}")
            val other = manager.add(SmangaConnectionProvider.ID, "Smanga unchanged ${UUID.randomUUID()}")
            val server = ShelfServer()
            var workName: String? = null
            val entryIds = mutableListOf<Long>()
            fun status(
                value: String,
            ) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_refresh", value) })
            suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
                while (true) {
                    findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let {
                        return@withTimeout it
                    }
                    delay(50)
                }
                error("Unreachable")
            }
            suspend fun click(label: String) {
                var node: AccessibilityNodeInfo? = awaitLabel(label)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            }
            suspend fun refresh() {
                click(context.stringResource(MR.strings.action_menu_overflow_description))
                click(context.stringResource(MR.strings.connection_refresh))
            }
            try {
                downloaded.set(false)
                autoDownload.set(false)
                manager.commitConfiguration(profile.id) { draft ->
                    SmangaPreferences(profile.id, draft).save(
                        server.address,
                        server.username,
                        "fixture-password",
                        server.accountId,
                    )
                }
                selected.set(profile.id)
                val source = sources.get(profile.id) as SmangaSource
                val owner = source.session().accountKey
                workName = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
                    source.id,
                    source.captureChapterOwner().key,
                )
                val name = workName
                entryIds += mangas.insertNetworkManga(
                    listOf(profile.id, other.id).map { id ->
                        Manga.create().copy(
                            source = id,
                            url = source.session().mangaUrl(4),
                            title = "Owned refresh fixture $id",
                            favorite = true,
                        )
                    },
                ).map { it.id }
                suspend fun catalogue() = repository.cachedCatalogue(profile.id, owner)
                    .sortedBy { it.group + it.key }
                suspend fun awaitWorker(count: Int) = withTimeout(20_000) {
                    while (true) {
                        val work = wm.getWorkInfosForUniqueWork(name).get()
                        if (work.size == count && work.all { it.state.isFinished }) {
                            assertTrue(work.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                            return@withTimeout work.map { it.id }.toSet()
                        }
                        delay(50)
                    }
                    error("Unreachable")
                }
                FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    awaitLabel(BOOK_TITLE)
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    assertEquals(0, server.chapterRequests.get())
                    refresh()
                    val firstWork = awaitWorker(1)
                    assertEquals(1, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    assertEquals(1, server.chapterRequests.get())
                    status("overflow-refresh-to-worker;owned-chapters=1;other-chapters=0")
                    val complete = catalogue()
                    assertTrue(complete.any { it.group.startsWith("shelf/") })
                    val reads = server.mangaRequests.get()
                    server.failManga.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.smanga_error_server))
                    delay(1_500)
                    assertEquals(reads + 1, server.mangaRequests.get())
                    assertEquals(complete, catalogue())
                    assertEquals(firstWork, wm.getWorkInfosForUniqueWork(name).get().map { it.id }.toSet())
                    assertEquals(1, server.chapterRequests.get())
                    awaitLabel(BOOK_TITLE)
                    status("failed-refresh-full-catalogue-equal;no-worker;old-content-visible")
                    server.failManga.set(false)
                    server.chapterCount.set(2)
                    click(context.stringResource(MR.strings.action_retry))
                    awaitWorker(2)
                    assertEquals(2, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    assertEquals(2, server.chapterRequests.get())
                    assertTrue(complete != catalogue())
                    assertEquals(0, server.businessWrites.get())
                    assertTrue(
                        server.requestPaths.all {
                            it in setOf(
                                "/fixture/api/login",
                                "/fixture/api/user/me",
                                "/fixture/api/media",
                                "/fixture/api/manga",
                                "/fixture/api/chapter",
                                "/fixture/api/opds/manga/4/cover",
                            )
                        },
                    )
                    status("snackbar-retry-to-worker;owned-chapters=2;other=0;business-writes=0")
                }
            } finally {
                try {
                    workName?.let { name ->
                        wm.cancelUniqueWork(name).result.get()
                        withTimeout(10_000) {
                            while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(25)
                        }
                    }
                    val notifications = context.getSystemService(android.app.NotificationManager::class.java)
                    entryIds.forEach { notifications.cancel(it.hashCode()) }
                    for (id in listOf(profile.id, other.id)) {
                        manager.remove(id).getOrThrow()
                        mangas.deleteMangaBySourceId(id)
                        repository.removeConnection(id)
                    }
                } finally {
                    if (hadSelected) selected.set(oldSelected) else selected.delete()
                    if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                    if (hadAutoDownload) autoDownload.set(oldAutoDownload) else autoDownload.delete()
                    server.close()
                    status("owned-profiles-sql-work-server-removed;preferences-restored")
                }
            }
        }

    @Test
    fun failedNonemptyShelfRefreshRetainsCompleteGenerationAcrossProcessRestart(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("shelfRestartPhase")
        org.junit.Assume.assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("shelfRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val file = java.io.File(context.cacheDir, "smanga-shelf-restart-$run.json")
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<SmangaRepository>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_restart", value) })
        fun save(value: JSONObject) = file.outputStream().use {
            it.write(value.toString().toByteArray())
            it.fd.sync()
        }
        if (phase == "cleanup") {
            if (file.exists()) {
                val saved = JSONObject(file.readText())
                val id = saved.getLong("id")
                saved.optString("work").takeIf { it.isNotEmpty() }?.let { name ->
                    val wm = androidx.work.WorkManager.getInstance(context)
                    wm.cancelUniqueWork(name).result.get()
                    withTimeout(10_000) {
                        while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(25)
                    }
                }
                manager.profiles().firstOrNull { it.id == id }?.let {
                    assertEquals("Smanga shelf restart $run", it.name)
                    manager.remove(id).getOrThrow()
                }
                Injekt.get<MangaRepository>().deleteMangaBySourceId(id)
                repository.removeConnection(id)
                if (saved.getBoolean("hadSelected")) selected.set(saved.getLong("selected")) else selected.delete()
                if (saved.getBoolean(
                        "hadDownloaded",
                    )
                ) {
                    downloaded.set(saved.getBoolean("downloaded"))
                } else {
                    downloaded.delete()
                }
                Injekt.get<ConnectionPreferences>().flush()
                check(file.delete())
            }
            status("owned-profile-sql-work-checkpoint-removed;preferences-restored")
            return@runBlocking
        }
        val saved: JSONObject
        val server: ShelfServer
        if (phase == "prepare") {
            check(!file.exists())
            server = ShelfServer()
            val profile = manager.add(SmangaConnectionProvider.ID, "Smanga shelf restart $run")
            saved = JSONObject().put("id", profile.id).put("pid", android.os.Process.myPid())
                .put("hadSelected", selected.isSet()).put("selected", selected.get())
                .put("hadDownloaded", downloaded.isSet()).put("downloaded", downloaded.get())
                .put("port", server.address.toHttpUrl().port).put("accountId", server.accountId)
                .put("username", server.username)
            save(saved)
        } else {
            saved = JSONObject(file.readText())
            assertTrue(saved.getInt("pid") != android.os.Process.myPid())
            server = ShelfServer(saved.getInt("port"), saved.getLong("accountId"), saved.getString("username"))
                .apply { failManga.set(true) }
        }
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
            while (true) {
                findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let {
                    return@withTimeout it
                }
                delay(50)
            }
            error("Unreachable")
        }
        suspend fun click(label: String) {
            var node: AccessibilityNodeInfo? = awaitLabel(label)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        suspend fun refresh() {
            click(context.stringResource(MR.strings.action_menu_overflow_description))
            click(context.stringResource(MR.strings.connection_refresh))
        }
        try {
            val id = saved.getLong("id")
            if (phase == "prepare") {
                downloaded.set(false)
                manager.commitConfiguration(id) { draft ->
                    SmangaPreferences(
                        id,
                        draft,
                    ).save(server.address, server.username, "fixture-password", server.accountId)
                }
                selected.set(id)
            }
            val source = sources.get(id) as SmangaSource
            val owner = source.session().accountKey
            val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, source.captureChapterOwner().key)
            saved.put("work", name)
            save(saved)
            val wm = androidx.work.WorkManager.getInstance(context)
            suspend fun completeSnapshot(): String {
                val result = org.json.JSONArray()
                val catalogue = repository.cachedCatalogue(id, owner)
                assertTrue(catalogue.any { it.group.startsWith("shelf/") })
                val media = checkNotNull(repository.cache(id, owner, "media", "data"))
                val entries = catalogue + koharia.domain.smanga.SmangaCatalogueCacheEntry(
                    "media",
                    media.key,
                    media.payload,
                    media.updatedAt,
                )
                for (entry in entries.sortedBy { it.group + it.key }) {
                    val cache = checkNotNull(repository.cache(id, owner, entry.group, entry.key))
                    result.put(
                        org.json.JSONArray(
                            listOf(entry.group, entry.key, entry.payload, entry.updatedAt, cache.generation),
                        ),
                    )
                }
                assertTrue(result.length() > 1)
                return result.toString()
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(SmangaLibraryScreen(id, null, false)) } }
                }
                awaitLabel(BOOK_TITLE)
                if (phase == "prepare") {
                    val complete = completeSnapshot()
                    saved.put("snapshot", complete).put("owner", owner)
                    save(saved)
                    Injekt.get<ConnectionPreferences>().flush()
                    server.failManga.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.smanga_error_server))
                    assertEquals(complete, completeSnapshot())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    awaitLabel(BOOK_TITLE)
                    status(
                        "prepared-failed-refresh;full-nonempty-sql-generation-retained;pid=" +
                            android.os.Process.myPid(),
                    )
                } else {
                    delay(2_000)
                    assertEquals(saved.getString("owner"), owner)
                    assertEquals(saved.getString("snapshot"), completeSnapshot())
                    assertEquals(0, server.mediaRequests.get())
                    assertEquals(0, server.mangaRequests.get())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    status("new-pid-warm-shelf;complete-generation-equal;directory=0;pid=" + android.os.Process.myPid())
                    server.failManga.set(false)
                    server.shelfTitle = "Recovered shelf fixture book"
                    refresh()
                    awaitLabel(server.shelfTitle)
                    withTimeout(15_000) {
                        while (wm.getWorkInfosForUniqueWork(name).get().none {
                                it.state == androidx.work.WorkInfo.State.SUCCEEDED
                            }
                        ) {
                            delay(50)
                        }
                    }
                    assertEquals(1, server.mediaRequests.get())
                    assertEquals(1, server.mangaRequests.get())
                    assertTrue(saved.getString("snapshot") != completeSnapshot())
                    status("new-pid-explicit-refresh-publishes-new-content;worker-succeeded")
                }
                assertEquals(0, server.businessWrites.get())
                assertTrue(
                    server.requestPaths.all {
                        it in setOf(
                            "/fixture/api/login",
                            "/fixture/api/user/me",
                            "/fixture/api/media",
                            "/fixture/api/manga",
                            "/fixture/api/opds/manga/4/cover",
                        )
                    },
                )
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun officialServiceInitialFailureRetriesIntoPersistentEmptyShelf(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val bridge = checkNotNull(InstrumentationRegistry.getArguments().getString("smangaShelfBridge"))
        val url = URL(bridge)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == 20462)
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
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
            while (true) {
                findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let {
                    return@withTimeout it
                }
                delay(50)
            }
            error("Unreachable")
        }
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(SmangaConnectionProvider.ID, "Official empty shelf ${UUID.randomUUID()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            val setup = control()
            val address = setup.getString("server")
            require(address == "http://127.0.0.1:20461")
            manager.commitConfiguration(profile.id) { draft ->
                SmangaPreferences(
                    profile.id,
                    draft,
                ).save(address, setup.getString("username"), setup.getString("password"), setup.getLong("accountId"))
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as SmangaSource
            val owner = source.session().accountKey
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(context.stringResource(MR.strings.smanga_error_server))
                val failed = control("/state")
                assertTrue(failed.getInt("failures") > 0)
                assertEquals(0, failed.getInt("emptyResponses"))
                status("official-initial-503-visible")
                control("/allow")
                var node: AccessibilityNodeInfo? = awaitLabel(context.stringResource(MR.strings.action_retry))
                while (node != null && !node.isClickable) node = node.parent
                assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                val emptyLabel = context.stringResource(MR.strings.no_results_found)
                awaitLabel(emptyLabel)
                delay(1_000)
                val empty = control("/state")
                assertTrue(empty.getInt("emptyResponses") > 0)
                val repository = Injekt.get<SmangaRepository>()
                suspend fun shelfCache() = repository.cachedCatalogue(profile.id, owner)
                    .filter { it.group.startsWith("shelf/") }.sortedBy { it.group + it.key }
                val saved = shelfCache()
                assertTrue(saved.isNotEmpty())
                status("official-empty-response-persisted")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as SmangaSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().accountKey)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(SmangaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                val warm = control("/state")
                assertEquals(empty.getInt("catalogue"), warm.getInt("catalogue"))
                assertEquals(saved, shelfCache())
                assertEquals(0, warm.getInt("mutations"))
                status("official-new-source-activity-empty;directory=0;sql-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
                Injekt.get<SmangaRepository>().removeConnection(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    private fun findVisibleLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.refresh()
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            return node
        }
        for (index in 0 until node.childCount) findVisibleLabel(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun hasVisibleTitle(node: AccessibilityNodeInfo?, title: String): Boolean {
        if (node == null) return false
        node.refresh()
        if (node.isVisibleToUser && (node.text?.toString() == title || node.contentDescription?.toString() == title)) {
            return true
        }
        return (0 until node.childCount).any { hasVisibleTitle(node.getChild(it), title) }
    }

    @OptIn(InternalVoyagerApi::class)
    private suspend fun collectShelf(
        source: SmangaSource,
        label: String,
        verifyLoaded: suspend (
            SmangaLibraryScreenModel,
            AsyncPagingDataDiffer<StateFlow<Manga>>,
            List<Manga>,
        ) -> Unit = { _, _, _ -> },
    ): List<Manga> = withContext(Dispatchers.Main) {
        val holderKey = "smanga-shelf-fixture-${UUID.randomUUID()}"
        lateinit var initializationJob: Job
        val model = ScreenModelStore.getOrPut(holderKey, null) {
            SmangaLibraryScreenModel(source, null).also {
                initializationJob = checkNotNull(it.screenModelScope.coroutineContext[Job])
            }
        }
        val modelJob = checkNotNull(model.screenModelScope.coroutineContext[Job])
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<StateFlow<Manga>>() {
                override fun areItemsTheSame(oldItem: StateFlow<Manga>, newItem: StateFlow<Manga>) =
                    oldItem.value.url == newItem.value.url

                override fun areContentsTheSame(oldItem: StateFlow<Manga>, newItem: StateFlow<Manga>) =
                    oldItem.value == newItem.value
            },
            updateCallback = object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            },
        )
        var failure: Throwable? = null
        differ.addLoadStateListener { states ->
            (states.refresh as? LoadState.Error)?.error?.let { failure = it }
        }
        val collector = launch {
            try {
                model.pages.collectLatest(differ::submitData)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error
            }
        }
        try {
            assertTrue("$label must start with an active initialization scope", initializationJob.isActive)
            assertTrue("$label must have an active registered model scope", modelJob.isActive)
            val initial = withTimeoutOrNull(15_000) {
                while (true) {
                    val error = failure ?: model.state.value.error
                    if (error != null) throw AssertionError("$label failed: ${failureTypes(error)}")
                    val items = differ.snapshot().items
                    if (items.isNotEmpty()) return@withTimeoutOrNull items.map { it.value }
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList<Manga>()
            } ?: throw AssertionError("$label timed out: ${failureTypes(failure ?: model.state.value.error)}")
            verifyLoaded(model, differ, initial)
            initial
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                ScreenModelStore.onDisposeNavigator(holderKey)
                initializationJob.join()
                modelJob.join()
            }
        }
    }

    private fun failureTypes(error: Throwable?): String = error?.let {
        generateSequence(it) { cause ->
            cause.cause
        }.take(6).joinToString(" -> ") { cause -> cause.javaClass.simpleName }
    } ?: "no reported failure"

    private class ShelfServer(
        port: Int = 0,
        val accountId: Long = Random.nextLong(1, Int.MAX_VALUE.toLong()),
        val username: String = "cold-${UUID.randomUUID()}",
    ) : AutoCloseable {
        private val listener = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val address: String get() = "http://127.0.0.1:${listener.localPort}/fixture"
        val requestCount = AtomicInteger()
        val requestPaths = ConcurrentLinkedQueue<String>()
        val businessWrites = AtomicInteger()
        private val coverBytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val mediaRequests = AtomicInteger()
        val chapterRequests = AtomicInteger()
        val chapterCount = AtomicInteger(1)
        val mangaRequests = AtomicInteger()
        val opdsRequests = AtomicInteger()
        val emptySearchResponses = AtomicInteger()
        val failManga = AtomicBoolean()
        val emptyShelf = AtomicBoolean()

        @Volatile var shelfTitle: String = BOOK_TITLE
        private val mangaBody = buildJsonObject {
            put("code", 200)
            put("count", 1)
            put(
                "list",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("mangaId", 4)
                            put("mediaId", 2)
                            put("mangaName", BOOK_TITLE)
                            put("intro", "fixture ".repeat(4096))
                            put("chapterCount", 1)
                        },
                    )
                },
            )
        }.toString().toByteArray()
        val mangaBodySize: Int get() = mangaBody.size

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: IOException) {
                        break
                    }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use(::respond)
                        } catch (_: IOException) {
                            // Cancellation and the intentionally failing old implementation close the socket.
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } finally {
                            sockets -= socket
                        }
                    }
                }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input)?.split(' ') ?: return
            val target = requestLine.getOrNull(1) ?: return
            var remaining = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    remaining =
                        line.substringAfter(':').trim().toInt()
                }
            }
            require(remaining in 0..16_384)
            while (remaining > 0) {
                if (input.read() == -1) return
                remaining--
            }
            val url = address.toHttpUrl().resolve(target) ?: return
            val path = url.encodedPath
            val emptySearch = path == "/fixture/api/manga" && url.queryParameter("keyWord") == "missing"
            requestCount.incrementAndGet()
            requestPaths += path
            if (requestLine.first() !in listOf("GET", "HEAD") && path != "/fixture/api/login") {
                businessWrites.incrementAndGet()
            }
            var status = 200
            var contentType = "application/json"
            val bytes = when (path) {
                "/fixture/api/opds/manga/4/cover" -> {
                    contentType = "image/png"
                    coverBytes
                }
                "/fixture/api/login", "/fixture/api/user/me" -> buildJsonObject {
                    put("code", 200)
                    put(
                        "data",
                        buildJsonObject {
                            put("userId", accountId)
                            put("userName", username)
                            put("role", "admin")
                            put("token", "fixture-token")
                        },
                    )
                }.toString().toByteArray()
                "/fixture/api/media" -> {
                    mediaRequests.incrementAndGet()
                    (
                        """{"code":200,"count":1,"list":[""" +
                            """{"mediaId":2,"mediaName":"Fixture media","mangaCount":1}]}"""
                        ).toByteArray()
                }
                "/fixture/api/manga" -> {
                    mangaRequests.incrementAndGet()
                    when {
                        failManga.get() -> {
                            status = 503
                            """{"code":503}""".toByteArray()
                        }
                        emptySearch || emptyShelf.get() -> """{"code":200,"count":0,"list":[]}""".toByteArray()
                        else -> mangaBody.toString(Charsets.UTF_8).replace(BOOK_TITLE, shelfTitle).toByteArray()
                    }
                }
                "/fixture/api/chapter" -> {
                    chapterRequests.incrementAndGet()
                    assertEquals("4", url.queryParameter("mangaId"))
                    buildJsonObject {
                        put("code", 200)
                        put("count", chapterCount.get())
                        put(
                            "list",
                            buildJsonArray {
                                repeat(chapterCount.get()) { index ->
                                    add(
                                        buildJsonObject {
                                            put("chapterId", 10 + index)
                                            put("mangaId", 4)
                                            put("mediaId", 2)
                                            put("chapterName", "Fixture chapter ${index + 1}")
                                            put("chapterNumber", index + 1)
                                            put("pageCount", 3)
                                            put("chapterType", "cbz")
                                        },
                                    )
                                }
                            },
                        )
                    }.toString().toByteArray()
                }
                "/fixture/api/opds" -> {
                    opdsRequests.incrementAndGet()
                    contentType = "application/atom+xml"
                    (
                        """<feed xmlns="http://www.w3.org/2005/Atom">""" +
                            "<id>fixture</id><title>Fixture</title><!--" +
                            "fixture ".repeat(4096) + "--></feed>"
                        ).toByteArray()
                }
                else -> {
                    status = 404
                    """{"code":404}""".toByteArray()
                }
            }
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 $status Fixture\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            if (path == "/fixture/api/manga" || path == "/fixture/api/opds") Thread.sleep(200)
            output.write(bytes)
            output.flush()
            if (emptySearch && status == 200) emptySearchResponses.incrementAndGet()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 8192) {
                val byte = input.read()
                if (byte == -1) return null
                if (byte == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                bytes.write(byte)
            }
            throw IOException("Fixture request line exceeds its bound")
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }

    private companion object {
        const val BOOK_TITLE = "Cold shelf fixture book"
    }
}
