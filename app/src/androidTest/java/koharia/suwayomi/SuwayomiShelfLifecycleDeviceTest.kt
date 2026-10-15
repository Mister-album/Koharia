package koharia.suwayomi

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiConnectionProvider
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.ui.SuwayomiLibraryScreen
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SuwayomiShelfLifecycleDeviceTest {
    @Test
    fun warmLibraryScreenSurvivesSourceAndActivityRecreationWithoutRequests(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val repository = Injekt.get<SuwayomiRepository>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(SuwayomiConnectionProvider.ID, "Warm lifecycle ${UUID.randomUUID()}")
        val server = UnexpectedRequestServer()
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                SuwayomiPreferences(profile.id, draft).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as SuwayomiSource
            val session = source.session()
            val expected = SuwayomiCatalog(
                session.identity,
                repository,
                SuwayomiBrowseCacheDeviceTest.DirectoryFixture(),
                Injekt.get<Json>(),
                {},
            ).shelf()
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                suspend fun verify(phase: String) {
                    withTimeout(15_000) {
                        while (!hasTitle(instrumentation.uiAutomation.rootInActiveWindow)) delay(50)
                    }
                    delay(2_500)
                    assertEquals("$phase must not connect to the service", 0, server.requests.get())
                    assertEquals(expected, source.session().catalog.shelf())
                    status("$phase;directory=0;http=0")
                }
                verify("warm-shelf")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as SuwayomiSource
                assertTrue(source !== previous)
                verify("source-recreated")
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                verify("foreground-resume")
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                verify("activity-recreated")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                repository.removeConnection(profile.id)
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                server.close()
                status("owned-profile-sql-server-removed;preferences-restored")
            }
        }
    }

    @Test
    fun ordinaryRefreshSchedulesOwnedChaptersAndRetainsCompleteShelfAfterFailure(): Unit =
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val manager = Injekt.get<ConnectionProfileManager>()
            val sources = Injekt.get<SourceManager>()
            val repository = Injekt.get<SuwayomiRepository>()
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
            val profile = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi refresh ${UUID.randomUUID()}")
            val other = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi unchanged ${UUID.randomUUID()}")
            val server = DirectoryServer()
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
                    SuwayomiPreferences(profile.id, draft).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
                }
                selected.set(profile.id)
                val source = sources.get(profile.id) as SuwayomiSource
                val owner = source.session().identity.account
                workName = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
                    source.id,
                    source.captureChapterOwner().key,
                )
                val name = workName
                entryIds += mangas.insertNetworkManga(
                    listOf(profile.id, other.id).map { id ->
                        Manga.create().copy(
                            source = id,
                            url = source.session().identity.mangaUrl(3),
                            title = "Fixture Manga",
                            favorite = true,
                        )
                    },
                ).map { it.id }
                suspend fun catalogue() = checkNotNull(repository.cache(profile.id, owner, "shelf", "snapshot"))
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
                FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    awaitLabel("Fixture Manga")
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    refresh()
                    val firstWork = awaitWorker(1)
                    assertEquals(1, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    status("overflow-refresh-to-worker;owned-chapters=1;other-chapters=0")
                    val complete = catalogue()
                    val reads = server.seriesReads.get()
                    server.failShelf.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.suwayomi_error_server))
                    delay(1_500)
                    assertEquals(reads + 1, server.seriesReads.get())
                    assertEquals(complete, catalogue())
                    assertEquals(firstWork, wm.getWorkInfosForUniqueWork(name).get().map { it.id }.toSet())
                    awaitLabel("Fixture Manga")
                    status("failed-refresh-full-shelf-generation-equal;no-worker;old-content-visible")
                    server.failShelf.set(false)
                    server.chapterCount.set(2)
                    click(context.stringResource(MR.strings.action_retry))
                    awaitWorker(2)
                    assertEquals(2, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    assertTrue(complete != catalogue())
                    assertEquals(0, server.mutations.get())
                    assertEquals(0, server.unexpected.get())
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
                    Injekt.get<ConnectionPreferences>().flush()
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
        val file = java.io.File(context.cacheDir, "suwayomi-shelf-restart-$run.json")
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<SuwayomiRepository>()
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
                    assertEquals("Suwayomi shelf restart $run", it.name)
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
        val server: DirectoryServer
        if (phase == "prepare") {
            check(!file.exists())
            server = DirectoryServer()
            val profile = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi shelf restart $run")
            saved = JSONObject().put("id", profile.id).put("pid", android.os.Process.myPid())
                .put("hadSelected", selected.isSet()).put("selected", selected.get())
                .put("hadDownloaded", downloaded.isSet()).put("downloaded", downloaded.get())
                .put("port", java.net.URI(server.address).port)
            save(saved)
        } else {
            saved = JSONObject(file.readText())
            assertTrue(saved.getInt("pid") != android.os.Process.myPid())
            server = DirectoryServer(saved.getInt("port"))
                .apply { failShelf.set(true) }
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
                    SuwayomiPreferences(id, draft).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
                }
                selected.set(id)
            }
            val source = sources.get(id) as SuwayomiSource
            val owner = source.session().identity.account
            val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, source.captureChapterOwner().key)
            saved.put("work", name)
            save(saved)
            val wm = androidx.work.WorkManager.getInstance(context)
            suspend fun completeSnapshot(): String {
                val entry = checkNotNull(repository.cache(id, owner, "shelf", "snapshot"))
                return org.json.JSONArray(listOf(entry.key, entry.payload, entry.generation)).toString()
            }
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(id, null, false)) } }
                }
                awaitLabel("Fixture Manga")
                if (phase == "prepare") {
                    saved.put("owner", owner)
                    save(saved)
                    Injekt.get<ConnectionPreferences>().flush()
                    val before = completeSnapshot()
                    server.failShelf.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.suwayomi_error_server))
                    assertEquals(before, completeSnapshot())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    awaitLabel("Fixture Manga")
                    saved.put("snapshot", completeSnapshot())
                    save(saved)
                    status(
                        "prepared-failed-refresh;full-nonempty-sql-generation-retained;pid=" +
                            android.os.Process.myPid(),
                    )
                } else {
                    delay(2_000)
                    assertEquals(saved.getString("owner"), owner)
                    assertEquals(saved.getString("snapshot"), completeSnapshot())
                    assertEquals(0, server.directoryReads.get())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    status("new-pid-warm-shelf;complete-generation-equal;directory=0;pid=" + android.os.Process.myPid())
                    server.failShelf.set(false)
                    server.shelfTitle = "Recovered Suwayomi fixture"
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
                    assertEquals(2, server.directoryReads.get())
                    assertEquals(1, server.seriesReads.get())
                    assertTrue(saved.getString("snapshot") != completeSnapshot())
                    status("new-pid-explicit-refresh-publishes-new-content;worker-succeeded")
                }
                assertEquals(0, server.mutations.get())
                assertEquals(0, server.unexpected.get())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun ordinaryShelfRetriesInitialFailureAndRetainsSuccessfulEmptyCache(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<SuwayomiRepository>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val previousSelected = selected.get()
        val hadSelected = selected.isSet()
        val previousDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi empty retry ${UUID.randomUUID()}")
        val server = DirectoryServer().apply { failShelf.set(true) }
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
        val failureLabel = context.stringResource(MR.strings.suwayomi_error_server)
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                SuwayomiPreferences(profile.id, draft).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as SuwayomiSource
            val owner = source.session().identity.account
            suspend fun shelfCache() = repository.cache(profile.id, owner, "shelf", "snapshot")
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(failureLabel)
                delay(750)
                val initialFailures = server.seriesReads.get()
                assertTrue(initialFailures > 0)
                delay(750)
                assertEquals(
                    "Initial failure must settle before user retry",
                    initialFailures,
                    server.seriesReads.get(),
                )
                assertEquals(null, shelfCache())
                status("initial-503-no-successful-shelf;requests=$initialFailures")
                server.emptyShelf.set(true)
                server.failShelf.set(false)
                retry()
                awaitLabel(emptyLabel)
                assertEquals(initialFailures + 1, server.seriesReads.get())
                val successfulEmpty = shelfCache()
                checkNotNull(successfulEmpty)
                status("ui-retry-published-empty")
                val before = server.requestCount.get()
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as SuwayomiSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().identity.account)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                assertEquals(before, server.requestCount.get())
                assertEquals(successfulEmpty, shelfCache())
                status("new-source-activity-empty-cache;requests=0")
                server.failShelf.set(true)
                retry()
                awaitLabel(failureLabel)
                assertEquals(initialFailures + 2, server.seriesReads.get())
                assertEquals(successfulEmpty, shelfCache())
                status("ui-refresh-failure-retains-complete-empty-shelf")
                server.failShelf.set(false)
                server.emptyShelf.set(false)
                retry()
                awaitLabel("Fixture Manga")
                assertEquals(initialFailures + 3, server.seriesReads.get())
                assertTrue(successfulEmpty != shelfCache())
                assertEquals(0, server.mutations.get())
                assertEquals(0, server.unexpected.get())
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
    fun officialServiceInitialFailureRetriesIntoPersistentEmptyShelf(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val bridge = checkNotNull(InstrumentationRegistry.getArguments().getString("suwayomiShelfBridge"))
        val url = URL(bridge)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == 20162)
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
        val profile = manager.add(SuwayomiConnectionProvider.ID, "Official empty shelf ${UUID.randomUUID()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            val setup = control()
            val address = setup.getString("server")
            require(address == "http://127.0.0.1:20161")
            manager.commitConfiguration(profile.id) { draft ->
                SuwayomiPreferences(profile.id, draft).save(address, "", SuwayomiAuthMode.NONE, "", "")
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as SuwayomiSource
            val owner = source.session().identity.account
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                try {
                    awaitLabel(context.stringResource(MR.strings.suwayomi_error_server))
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    runCatching { source.session().catalog.shelf() }.exceptionOrNull()?.let {
                        status("official-read-diagnostic=${it.stackTraceToString()}")
                    }
                    throw error
                }
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
                val repository = Injekt.get<SuwayomiRepository>()
                val saved = repository.cache(profile.id, owner, "shelf", "snapshot")
                checkNotNull(saved)
                status("official-empty-response-persisted")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as SuwayomiSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().identity.account)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiPreviewTheme { Navigator(SuwayomiLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                val warm = control("/state")
                assertEquals(empty.getInt("catalogue"), warm.getInt("catalogue"))
                assertEquals(saved, repository.cache(profile.id, owner, "shelf", "snapshot"))
                assertEquals(0, warm.getInt("mutations"))
                status("official-new-source-activity-empty;directory=0;sql-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
                Injekt.get<SuwayomiRepository>().removeConnection(profile.id)
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

    private class DirectoryServer(port: Int = 0) : AutoCloseable {
        private val listener = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val address get() = "http://127.0.0.1:${listener.localPort}"
        val requestCount = AtomicInteger()
        val seriesReads = AtomicInteger()
        val directoryReads = AtomicInteger()
        val chapterCount = AtomicInteger(1)

        @Volatile var shelfTitle = "Fixture Manga"
        val mutations = AtomicInteger()
        val unexpected = AtomicInteger()
        val failShelf = AtomicBoolean()
        val emptyShelf = AtomicBoolean()

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use(::respond)
                        } catch (_: IOException) {
                            // Source replacement cancels an in-flight fixture response.
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
            fun line(): String = buildString {
                while (true) {
                    val byte = input.read()
                    if (byte == -1 || byte == 10) break
                    if (byte != 13) append(byte.toChar())
                }
            }
            val request = line().split(' ')
            if (request.size < 2) return
            val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val size = headers["content-length"]?.toInt() ?: 0
            require(size in 0..65_536)
            val body = ByteArray(size).also { java.io.DataInputStream(input).readFully(it) }
            val query = if (body.isEmpty()) "" else JSONObject(body.decodeToString()).optString("query")
            requestCount.incrementAndGet()
            var code = 200
            val response = when {
                query.trimStart().startsWith("mutation") -> {
                    mutations.incrementAndGet()
                    code = 403
                    "{}"
                }
                request[1] != "/api/graphql" || request[0] != "POST" -> {
                    unexpected.incrementAndGet()
                    code = 404
                    "{}"
                }
                query.contains("chapters(condition:") -> {
                    require(JSONObject(body.decodeToString()).getJSONObject("variables").getInt("id") == 3)
                    val chapters = (1..chapterCount.get()).joinToString(",") { index ->
                        """{"id":${100 + index},"mangaId":3,"name":"Chapter $index","chapterNumber":$index,
                        "sourceOrder":$index,"pageCount":10,"isRead":false,"isDownloaded":false}
                        """.trimIndent()
                    }
                    """{"data":{"chapters":{"nodes":[$chapters]}}}"""
                }
                query.contains("categories(order:") -> {
                    directoryReads.incrementAndGet()
                    """{"data":{"categories":{"nodes":[]}}}"""
                }
                query.contains("mangas(condition:{inLibrary:true}") -> {
                    seriesReads.incrementAndGet()
                    directoryReads.incrementAndGet()
                    when {
                        failShelf.get() -> {
                            code = 503
                            "{}"
                        }
                        emptyShelf.get() -> """
                            {"data":{"mangas":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}
                        """.trimIndent()
                        else -> """
                            {"data":{"mangas":{"nodes":[{"id":3,"title":"$shelfTitle","sourceId":12,
                            "inLibrary":true}],"pageInfo":{"hasNextPage":false}}}}
                        """.trimIndent()
                    }
                }
                else -> {
                    unexpected.incrementAndGet()
                    code = 404
                    "{}"
                }
            }.encodeToByteArray()
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        ).encodeToByteArray(),
                )
                write(response)
                flush()
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

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

    private fun hasTitle(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.refresh()
        if (node.isVisibleToUser && (
                node.text?.toString() == "Fixture Manga" || node.contentDescription?.toString() == "Fixture Manga"
                )
        ) {
            return true
        }
        return (0 until node.childCount).any { hasTitle(node.getChild(it)) }
    }
}
