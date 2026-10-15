package koharia.kavita

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionShelfUpdates
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.ui.KavitaLibraryScreen
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KavitaContentEventsDeviceTest {
    @Test
    fun officialServiceInitialFailureRetriesIntoPersistentEmptyShelf(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val bridge = checkNotNull(InstrumentationRegistry.getArguments().getString("kavitaShelfBridge"))
        val url = URL(bridge)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == 20062)
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
        val profile = manager.add(KavitaConnectionProvider.ID, "Official empty shelf ${UUID.randomUUID()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            val setup = control()
            val address = setup.getString("server")
            require(address == "http://127.0.0.1:20061/")
            val key = setup.getString("key")
            val account = KavitaApiClient(OkHttpClient(), address, key, "official-shelf-${profile.id}").use {
                it.getAccount()
            }
            assertTrue(account.kavitaVersion.startsWith("0.8.8"))
            manager.commitConfiguration(profile.id) { draft ->
                KavitaPreferences(profile.id, draft).save(address, key, account)
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KavitaSource
            val owner = source.session().accountKey
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) } }
                }
                awaitLabel(context.stringResource(MR.strings.kavita_error_connection))
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
                val repository = Injekt.get<KavitaRepository>()
                val group = "shelf/" + ("Series/all-v2" + empty.getString("filter")).encodeUtf8().sha256().hex()
                val saved = repository.entries(profile.id, owner, group)
                assertTrue(saved.isNotEmpty())
                status("official-empty-response-persisted")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KavitaSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().accountKey)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) } }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                val warm = control("/state")
                assertEquals(empty.getInt("catalogue"), warm.getInt("catalogue"))
                assertEquals(saved, repository.entries(profile.id, owner, group))
                assertEquals(0, warm.getInt("mutations"))
                status("official-new-source-activity-empty;directory=0;sql-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
                Injekt.get<KavitaRepository>().removeConnection(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    @Test
    fun registeredSourceProcessesSignalRContentWithoutRefreshingOnReconnect(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<koharia.connection.ConnectionPreferences>()
        val previousActive = connections.activeConnectionId.get()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        // A process killed during a previous invocation cannot run its finally block.
        manager.profiles().filter {
            it.providerId == KavitaConnectionProvider.ID && it.name == "SignalR content fixture"
        }.forEach {
            manager.remove(it.id).getOrThrow()
            mangas.deleteMangaBySourceId(it.id)
        }
        val wm = WorkManager.getInstance(context)
        val autoDownload = Injekt.get<DownloadPreferences>().downloadNewChapters
        val oldAutoDownload = autoDownload.get()
        val autoDownloadWasSet = autoDownload.isSet()
        val profile = manager.add(KavitaConnectionProvider.ID, "SignalR content fixture")
        var workName: String? = null
        var mangaId: Long? = null
        try {
            autoDownload.set(false)
            Server().use { server ->
                KavitaPreferences(profile.id).save(server.root, "fixture", server.account)
                val source = withTimeout(10_000) {
                    var found: KavitaSource?
                    do {
                        found = Injekt.get<SourceManager>().get(profile.id) as? KavitaSource
                        if (found == null) delay(20)
                    } while (found == null)
                    found
                }
                source.reload()
                withTimeout(15_000) { while (server.handshakes.get() == 0) delay(20) }
                val name = LibraryUpdateJob.workName(profile.id, source.captureChapterOwner().key)
                workName = name
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = source.session().identity.series(2),
                            title = "SignalR series fixture",
                            favorite = true,
                        ),
                    ),
                ).single()
                mangaId = manga.id
                source.session().catalog.series(2)
                source.session().catalog.volumes(2)
                val reads = server.catalogReads.get()
                val firstHandshake = server.handshakes.get()
                server.disconnect()
                withTimeout(20_000) { while (server.handshakes.get() == firstHandshake) delay(20) }
                assertEquals(reads, server.catalogReads.get())
                assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())

                val beforeCover = ConnectionShelfUpdates.version(profile.id)
                server.event("CoverUpdate")
                withTimeout(10_000) { while (ConnectionShelfUpdates.version(profile.id) == beforeCover) delay(20) }
                assertEquals(reads, server.catalogReads.get())
                assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                assertTrue(chapters.getChapterByMangaId(manga.id).isEmpty())

                server.event("SeriesUpdated")
                withTimeout(20_000) {
                    while (true) {
                        val work = wm.getWorkInfosForUniqueWork(name).get()
                        if (work.isNotEmpty() && work.all { it.state.isFinished }) {
                            assertTrue(work.all { it.state == WorkInfo.State.SUCCEEDED })
                            break
                        }
                        delay(50)
                    }
                }
                val saved = chapters.getChapterByMangaId(manga.id).single()
                assertEquals(4L, source.session().identity.chapter(saved.url).chapterId)
                assertTrue(server.catalogReads.get() > reads)
                assertEquals(0, server.unexpectedRequests.get())
                assertEquals(0, server.mutations.get())
                source.close()
            }
        } finally {
            workName?.let { wm.cancelUniqueWork(it).result.get() }
            mangaId?.let {
                context.getSystemService(android.app.NotificationManager::class.java).cancel(it.hashCode())
                mangas.deleteMangaById(it)
            }
            manager.remove(profile.id).getOrThrow()
            if (manager.profiles().any { it.id == previousActive }) connections.activeConnectionId.set(previousActive)
            if (autoDownloadWasSet) autoDownload.set(oldAutoDownload) else autoDownload.delete()
        }
    }

    @Test
    fun actualWarmShelfKeepsDirectoryCacheAcrossSourceResumeAndSignalRReconnect(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("app.koharia.dev.devicefixture", instrumentation.targetContext.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(KavitaConnectionProvider.ID, "Warm SignalR ${UUID.randomUUID()}")
        val server = Server()
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                KavitaPreferences(profile.id, draft).save(server.root, "fixture", server.account)
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KavitaSource
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) } }
                }
                suspend fun awaitShelf() = withTimeout(15_000) {
                    while (!hasWarmTitle(instrumentation.uiAutomation.rootInActiveWindow)) delay(50)
                }
                awaitShelf()
                withTimeout(15_000) { while (server.handshakes.get() == 0) delay(25) }
                assertEquals(2, server.shelfReads.get())
                val repository = Injekt.get<KavitaRepository>()
                val owner = source.session().accountKey
                val saved = checkNotNull(repository.cache(profile.id, owner, "libraries", "data"))
                suspend fun verify(phase: String) {
                    awaitShelf()
                    delay(2_500)
                    assertEquals("$phase directory", 2, server.shelfReads.get())
                    assertEquals("$phase writes", 0, server.mutations.get())
                    assertEquals("$phase unknown", 0, server.unexpectedRequests.get())
                    assertEquals(saved, repository.cache(profile.id, owner, "libraries", "data"))
                    status("$phase;directory=0;progress=" + server.progressReads.get())
                }
                val original = source
                val handshake = server.handshakes.get()
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KavitaSource
                assertTrue(source !== original)
                assertEquals(owner, source.session().accountKey)
                withTimeout(15_000) { while (server.handshakes.get() == handshake) delay(25) }
                verify("source-recreated")
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                verify("foreground-resume")
                val beforeReconnect = server.handshakes.get()
                server.disconnect()
                withTimeout(20_000) { while (server.handshakes.get() == beforeReconnect) delay(25) }
                verify("signalr-reconnected")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
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
            val repository = Injekt.get<KavitaRepository>()
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
            val profile = manager.add(KavitaConnectionProvider.ID, "Kavita refresh ${UUID.randomUUID()}")
            val other = manager.add(KavitaConnectionProvider.ID, "Kavita unchanged ${UUID.randomUUID()}")
            val server = Server()
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
                    KavitaPreferences(profile.id, draft).save(server.root, "fixture", server.account)
                }
                selected.set(profile.id)
                val source = sources.get(profile.id) as KavitaSource
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
                            url = source.session().identity.series(2),
                            title = "Warm shelf series",
                            favorite = true,
                        )
                    },
                ).map { it.id }
                suspend fun catalogue() = repository.entries(
                    profile.id,
                    owner,
                    "shelf/" + ("Series/all-v2" + checkNotNull(server.shelfFilter)).encodeUtf8().sha256().hex(),
                ).sortedBy { it.key }
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
                            TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    awaitLabel("Warm shelf series")
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    refresh()
                    val firstWork = awaitWorker(1)
                    assertEquals(1, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    status("overflow-refresh-to-worker;owned-chapters=1;other-chapters=0")
                    val complete = catalogue()
                    assertTrue(complete.isNotEmpty())
                    val reads = server.seriesReads.get()
                    server.failShelf.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.kavita_error_connection))
                    delay(1_500)
                    assertEquals(reads + 1, server.seriesReads.get())
                    assertEquals(complete, catalogue())
                    assertEquals(firstWork, wm.getWorkInfosForUniqueWork(name).get().map { it.id }.toSet())
                    awaitLabel("Warm shelf series")
                    status("failed-refresh-full-shelf-generation-equal;no-worker;old-content-visible")
                    server.failShelf.set(false)
                    server.chapterCount.set(2)
                    click(context.stringResource(MR.strings.action_retry))
                    awaitWorker(2)
                    assertEquals(2, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    assertTrue(complete != catalogue())
                    assertEquals(0, server.mutations.get())
                    assertEquals(0, server.unexpectedRequests.get())
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
        val file = java.io.File(context.cacheDir, "kavita-shelf-restart-$run.json")
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val repository = Injekt.get<KavitaRepository>()
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
                    assertEquals("Kavita shelf restart $run", it.name)
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
        val server: Server
        if (phase == "prepare") {
            check(!file.exists())
            server = Server()
            val profile = manager.add(KavitaConnectionProvider.ID, "Kavita shelf restart $run")
            saved = JSONObject().put("id", profile.id).put("pid", android.os.Process.myPid())
                .put("hadSelected", selected.isSet()).put("selected", selected.get())
                .put("hadDownloaded", downloaded.isSet()).put("downloaded", downloaded.get())
                .put("port", java.net.URI(server.root).port)
            save(saved)
        } else {
            saved = JSONObject(file.readText())
            assertTrue(saved.getInt("pid") != android.os.Process.myPid())
            server = Server(saved.getInt("port"))
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
                    KavitaPreferences(id, draft).save(server.root, "fixture", server.account)
                }
                selected.set(id)
            }
            val source = sources.get(id) as KavitaSource
            val owner = source.session().accountKey
            val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, source.captureChapterOwner().key)
            saved.put("work", name)
            save(saved)
            val wm = androidx.work.WorkManager.getInstance(context)
            suspend fun completeSnapshot(): String {
                val result = org.json.JSONArray()
                for (group in listOf("libraries", saved.getString("shelfGroup"))) {
                    val entries = repository.entries(id, owner, group).sortedBy { it.key }
                    assertTrue(entries.isNotEmpty())
                    for (entry in entries) {
                        result.put(
                            org.json.JSONArray(listOf(group, entry.key, entry.payload, entry.generation, entry.stale)),
                        )
                    }
                }
                return result.toString()
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KavitaLibraryScreen(id, null, false)) } }
                }
                awaitLabel("Warm shelf series")
                if (phase == "prepare") {
                    val shelfGroup = "shelf/" + ("Series/all-v2" + checkNotNull(server.shelfFilter))
                        .encodeUtf8().sha256().hex()
                    saved.put("shelfGroup", shelfGroup).put("owner", owner)
                    save(saved)
                    Injekt.get<ConnectionPreferences>().flush()
                    val before = repository.entries(id, owner, shelfGroup).sortedBy { it.key }
                    assertTrue(before.isNotEmpty())
                    server.failShelf.set(true)
                    refresh()
                    awaitLabel(context.stringResource(MR.strings.kavita_error_connection))
                    assertEquals(before, repository.entries(id, owner, shelfGroup).sortedBy { it.key })
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    awaitLabel("Warm shelf series")
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
                    assertEquals(0, server.shelfReads.get())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    status("new-pid-warm-shelf;complete-generation-equal;directory=0;pid=" + android.os.Process.myPid())
                    server.failShelf.set(false)
                    server.shelfTitle = "Recovered Kavita fixture"
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
                    assertEquals(2, server.shelfReads.get())
                    assertEquals(1, server.seriesReads.get())
                    assertTrue(saved.getString("snapshot") != completeSnapshot())
                    status("new-pid-explicit-refresh-publishes-new-content;worker-succeeded")
                }
                assertEquals(0, server.mutations.get())
                assertEquals(0, server.unexpectedRequests.get())
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
        val repository = Injekt.get<KavitaRepository>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val previousSelected = selected.get()
        val hadSelected = selected.isSet()
        val previousDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add(KavitaConnectionProvider.ID, "Kavita empty retry ${UUID.randomUUID()}")
        val server = Server().apply { failShelf.set(true) }
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
        val failureLabel = context.stringResource(MR.strings.kavita_error_connection)
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                KavitaPreferences(profile.id, draft).save(server.root, "fixture", server.account)
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KavitaSource
            val owner = source.session().accountKey
            suspend fun shelfCache() = repository.entries(
                profile.id,
                owner,
                "shelf/" + ("Series/all-v2" + checkNotNull(server.shelfFilter))
                    .encodeUtf8().sha256().hex(),
            ).sortedBy { it.key }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) }
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
                assertTrue(shelfCache().isEmpty())
                status("initial-503-no-successful-shelf;requests=$initialFailures")
                server.emptyShelf.set(true)
                server.failShelf.set(false)
                retry()
                awaitLabel(emptyLabel)
                assertEquals(initialFailures + 1, server.seriesReads.get())
                val successfulEmpty = shelfCache()
                assertTrue(successfulEmpty.isNotEmpty())
                status("ui-retry-published-empty")
                val before = server.shelfReads.get()
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KavitaSource
                assertTrue(source !== previous)
                assertEquals(owner, source.session().accountKey)
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                assertEquals(before, server.shelfReads.get())
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
                awaitLabel("Warm shelf series")
                assertEquals(initialFailures + 3, server.seriesReads.get())
                assertTrue(successfulEmpty != shelfCache())
                assertEquals(0, server.mutations.get())
                assertEquals(0, server.unexpectedRequests.get())
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

    private fun findVisibleLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.refresh()
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            return node
        }
        for (index in 0 until node.childCount) findVisibleLabel(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun hasWarmTitle(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.refresh()
        if (node.isVisibleToUser && (
                node.text?.toString() == "Warm shelf series" ||
                    node.contentDescription?.toString() == "Warm shelf series"
                )
        ) {
            return true
        }
        return (0 until node.childCount).any { hasWarmTitle(node.getChild(it)) }
    }

    /** Minimal wire fixture: HTTP auth/catalogue and RFC6455 frames carrying the SignalR JSON protocol. */
    private class Server(port: Int = 0) : AutoCloseable {
        private val listener = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val sendLock = Any()
        private var eventOutput: OutputStream? = null
        val root = "http://127.0.0.1:${listener.localPort}/"
        val account = KavitaAccount(7, "fixture", "fixture-token", listOf("Login"), "0.9.1.4")
        val handshakes = AtomicInteger()
        val catalogReads = AtomicInteger()
        val shelfReads = AtomicInteger()
        val seriesReads = AtomicInteger()
        val failShelf = AtomicBoolean()
        val chapterCount = AtomicInteger(1)
        val emptyShelf = AtomicBoolean()

        @Volatile var shelfTitle = "Warm shelf series"

        @Volatile var shelfFilter: String? = null
        val progressReads = AtomicInteger()
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
        val unexpectedRequests = AtomicInteger()
        val mutations = AtomicInteger()
        private val acceptor = Thread {
            while (!listener.isClosed) {
                try {
                    val socket = listener.accept()
                    sockets += socket
                    executor.submit {
                        socket.use {
                            try {
                                handle(it)
                            } catch (_: EOFException) {
                                // Client/session shutdown ends the websocket.
                            } catch (_: java.net.SocketException) {
                                // Both ends can initiate teardown.
                            } catch (error: Throwable) {
                                errors += error
                            } finally {
                                sockets -= it
                            }
                        }
                    }
                } catch (_: java.net.SocketException) {
                    check(listener.isClosed)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream().buffered())
            fun line(): String = buildString {
                while (true) {
                    val byte = input.readUnsignedByte()
                    if (byte == 10) break
                    if (byte != 13) append(byte.toChar())
                }
            }
            val request = line().split(' ')
            val method = request[0]
            val path = request[1].substringBefore('?')
            val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            if (method != "GET" && !(
                    method == "POST" && path in setOf(
                        "/api/Plugin/authenticate",
                        "/hubs/messages/negotiate",
                        "/api/Series/all-v2",
                    )
                    )
            ) {
                mutations.incrementAndGet()
            }
            if (path != "/api/Plugin/authenticate") check(headers["authorization"] == "Bearer fixture-token")
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            input.readFully(body)
            val output = socket.getOutputStream()
            if (path == "/hubs/messages") {
                val key = checkNotNull(headers["sec-websocket-key"])
                val digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                output.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(digest)}\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                val handshake = readFrame(input)
                check(handshake.contains("\"protocol\":\"json\""))
                synchronized(sendLock) {
                    frame(output, "{}\u001e")
                    eventOutput = output
                    handshakes.incrementAndGet()
                }
                while (true) readFrame(input)
            }
            if (path == "/api/Image/series-cover") {
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${coverBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.write(coverBytes)
                output.flush()
                return
            }
            var statusCode = 200
            val response = when (path) {
                "/api/Plugin/authenticate", "/api/Account/refresh-account" -> Json.encodeToString(account)
                "/hubs/messages/negotiate" -> """{"connectionToken":"fixture","negotiateVersion":1}"""
                "/api/Library/libraries" -> {
                    shelfReads.incrementAndGet()
                    """[{"id":1,"name":"Fixture"}]"""
                }
                "/api/Series/all-v2" -> {
                    shelfReads.incrementAndGet()
                    seriesReads.incrementAndGet()
                    shelfFilter = body.decodeToString()
                    when {
                        failShelf.get() -> {
                            statusCode = 503
                            "{}"
                        }
                        emptyShelf.get() -> "[]"
                        else -> """[{"id":2,"name":"Warm shelf series","libraryId":1,"format":1,"pages":10}]""".replace(
                            "Warm shelf series",
                            shelfTitle,
                        )
                    }
                }
                "/api/Series/2" -> {
                    catalogReads.incrementAndGet()
                    """{"id":2,"name":"Fixture","libraryId":1,"format":1}"""
                }
                "/api/Series/volumes" -> {
                    catalogReads.incrementAndGet()
                    progressReads.incrementAndGet()
                    val chapters = (0 until chapterCount.get()).joinToString(",") { index ->
                        val title = if (index == 0) "Chapter" else "Chapter ${index + 1}"
                        """{"id":${4 + index},"volumeId":3,"title":"$title","pages":10,"format":1}"""
                    }
                    """[{"id":3,"chapters":[$chapters]}]"""
                }
                else -> {
                    unexpectedRequests.incrementAndGet()
                    "{}"
                }
            }.encodeToByteArray()
            val pagination = if (path == "/api/Series/all-v2") {
                val total = if (emptyShelf.get()) 0 else 1
                "Pagination: {\"currentPage\":1,\"totalPages\":1,\"pageSize\":50,\"totalCount\":$total}\r\n"
            } else {
                ""
            }
            output.write(
                (
                    "HTTP/1.1 $statusCode Fixture\r\n$pagination" +
                        "Content-Type: application/json\r\nContent-Length: ${response.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            output.write(response)
            output.flush()
        }

        fun event(name: String) = synchronized(sendLock) {
            frame(
                checkNotNull(eventOutput),
                """{"type":1,"target":"$name","arguments":[{"body":{"seriesId":2}}]}""" + '\u001e',
            )
        }

        fun disconnect() = synchronized(sendLock) {
            frame(checkNotNull(eventOutput), "{\"type\":7}\u001e")
            eventOutput = null
        }

        private fun frame(output: OutputStream, text: String) {
            val bytes = text.encodeToByteArray()
            check(bytes.size < 126)
            output.write(0x81)
            output.write(bytes.size)
            output.write(bytes)
            output.flush()
        }

        private fun readFrame(input: DataInputStream): String {
            val opcode = input.readUnsignedByte() and 15
            val lengthByte = input.readUnsignedByte()
            check(lengthByte and 128 != 0)
            val length = when (val shortLength = lengthByte and 127) {
                126 -> input.readUnsignedShort()
                127 -> error("Oversized fixture client frame")
                else -> shortLength
            }
            val mask = ByteArray(4).also(input::readFully)
            val bytes = ByteArray(length).also(input::readFully)
            bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
            if (opcode == 8) throw EOFException()
            check(opcode == 1)
            return bytes.decodeToString()
        }

        override fun close() {
            listener.close()
            sockets.forEach { it.close() }
            acceptor.join(5_000)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
            check(errors.isEmpty()) { errors.joinToString { it.toString() } }
        }
    }
}
