package koharia.komga

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.komga.api.KomgaSseClient
import koharia.komga.ui.library.KomgaLibraryScreen
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KomgaShelfLifecycleDeviceTest {
    @Test
    fun defaultLibrarySettingsSaveRebindsOrdinaryShelfToSelectedLibrary(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val progress = Injekt.get<LibraryPreferences>().showLibraryReadProgress
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val oldProgress = progress.get()
        val hadProgress = progress.isSet()
        val profile = manager.add("komga", "Default library ${UUID.randomUUID()}")
        val server = Server().apply { settingsLibraries.set(true) }
        val navigation = java.util.concurrent.atomic.AtomicReference<Navigator>()
        fun status(
            value: String,
        ) = instrumentation.sendStatus(2, Bundle().apply { putString("library_setting", value) })
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
            while (true) {
                findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }
        suspend fun click(label: String) {
            var node: AccessibilityNodeInfo? = awaitLabel(label)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        suspend fun openLibraries() {
            withContext(Dispatchers.Main) {
                navigation.get().push(koharia.source.komga.KomgaServerSettingsScreen(profile.id))
            }
            val label = context.stringResource(MR.strings.komga_pref_default_libraries_title)
            withTimeout(15_000) {
                while (findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label) == null) {
                    scrollable(instrumentation.uiAutomation.rootInActiveWindow)
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(250)
                }
            }
            click(label)
            awaitLabel("Second fixture library")
        }
        try {
            downloaded.set(false)
            progress.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
            }
            selected.set(profile.id)
            val original = sources.get(profile.id) as KomgaSource
            val namespace = original.shelfCacheNamespace()
            val inventory = manager.profiles().map { it.id }.toSet()
            FixtureActivityLauncher.launch(koharia.epub.EpubTransitionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(KomgaLibraryScreen(profile.id, null, false)) {
                                navigation.set(it)
                                cafe.adriel.voyager.navigator.CurrentScreen()
                            }
                        }
                    }
                }
                awaitLabel("First library series")
                awaitLabel("Second library series")
                openLibraries()
                click("Second fixture library")
                click(context.getString(android.R.string.ok))
                assertTrue(original.configuredShelfLibraryIds().isEmpty())
                status("draft-selection-does-not-change-published-configuration")
                click(context.stringResource(MR.strings.action_save))
                withTimeout(15_000) {
                    while (sources.get(profile.id) === original || navigation.get().lastItem !is KomgaLibraryScreen) {
                        delay(50)
                    }
                }
                val replacement = sources.get(profile.id) as KomgaSource
                assertEquals(setOf("2"), replacement.configuredShelfLibraryIds())
                assertEquals(namespace, replacement.shelfCacheNamespace())
                assertEquals(inventory, manager.profiles().map { it.id }.toSet())
                assertEquals(profile.id, selected.get())
                awaitLabel("Second library series")
                delay(1_000)
                assertEquals(
                    null,
                    findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, "First library series"),
                )
                assertTrue(server.librarySelections.any { it == setOf("2") })
                status("actual-save-same-owner-new-source;selected-library-query-and-visible-content=2")
                openLibraries()
                click(context.stringResource(MR.strings.all))
                click(context.getString(android.R.string.ok))
                click(context.stringResource(MR.strings.action_save))
                awaitLabel("First library series")
                awaitLabel("Second library series")
                assertTrue((sources.get(profile.id) as KomgaSource).configuredShelfLibraryIds().isEmpty())
                assertEquals(inventory, manager.profiles().map { it.id }.toSet())
                assertEquals(0, server.writes.get())
                assertEquals(0, server.unknown.get())
                status("actual-save-all-restores-both-libraries;business-writes=0;unknown=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                if (hadProgress) progress.set(oldProgress) else progress.delete()
                Injekt.get<ConnectionPreferences>().flush()
                server.close()
                status("owned-profile-sql-server-removed;preferences-restored")
            }
        }
    }

    @Test
    fun ordinaryWarmShelfKeepsCacheAcrossSourceResumeAndSseReconnect(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val progress = Injekt.get<LibraryPreferences>().showLibraryReadProgress
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val oldProgress = progress.get()
        val hadProgress = progress.isSet()
        val profile = manager.add("komga", "Warm shelf ${UUID.randomUUID()}")
        val server = Server()
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            progress.set(true)
            manager.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KomgaSource
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) } }
                }
                suspend fun awaitShelf() = withTimeout(15_000) {
                    while (!hasTitle(instrumentation.uiAutomation.rootInActiveWindow)) delay(50)
                }
                awaitShelf()
                delay(2_500)
                val directory = server.directory.get()
                assertTrue(directory > 0)
                assertTrue(server.progress.get() > 0)
                val namespace = source.shelfCacheNamespace()
                suspend fun verify(phase: String) {
                    awaitShelf()
                    delay(2_500)
                    assertEquals("$phase directory: ${server.paths}", directory, server.directory.get())
                    assertEquals("$phase writes", 0, server.writes.get())
                    assertEquals("$phase unknown: ${server.paths}", 0, server.unknown.get())
                    assertEquals(namespace, source.shelfCacheNamespace())
                    status("$phase;directory=0;progress=" + server.progress.get())
                }
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KomgaSource
                assertTrue(source !== previous)
                verify("source-recreated")
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                verify("foreground-resume")
                val job = SupervisorJob()
                val scope = CoroutineScope(job + Dispatchers.Main.immediate)
                val lifecycle = ProcessLifecycleOwner.get()
                val client = KomgaSseClient(
                    context,
                    Injekt.get(),
                    lazy { Injekt.get() },
                    { source.baseUrl },
                    { source.id },
                    { source.currentReadiumHeaders() },
                    { false },
                    { source.client },
                )
                try {
                    withContext(Dispatchers.Main) {
                        client.start(scope)
                        client.onStart(lifecycle)
                    }
                    withTimeout(10_000) { while (server.sse.get() == 0) delay(25) }
                    val first = server.sse.get()
                    withContext(Dispatchers.Main) { client.reconnect() }
                    withTimeout(10_000) { while (server.sse.get() == first) delay(25) }
                    verify("sse-reconnected")
                } finally {
                    withContext(Dispatchers.Main) {
                        client.onStop(lifecycle)
                        lifecycle.lifecycle.removeObserver(client)
                    }
                    job.cancel()
                    job.join()
                }
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                if (hadProgress) progress.set(oldProgress) else progress.delete()
                server.close()
                status("owned-profile-sql-server-removed;preferences-restored")
            }
        }
    }

    @Test
    fun ordinaryColdShelfRetriesIntoPersistentEmptyResults(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add("komga", "Empty retry ${UUID.randomUUID()}")
        val server = Server().apply { failShelf.set(true) }
        val emptyLabel = context.stringResource(MR.strings.no_results_found)
        val retryLabel = context.stringResource(MR.strings.action_retry)
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
        try {
            downloaded.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KomgaSource
            val namespace = source.shelfCacheNamespace()
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) } }
                }
                awaitLabel(retryLabel)
                assertTrue(server.seriesReads.get() > 0)
                assertEquals(null, findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, emptyLabel))
                delay(750)
                val initialFailures = server.seriesReads.get()
                delay(750)
                assertEquals(initialFailures, server.seriesReads.get())
                status("initial-503-error-ui;requests=$initialFailures")
                server.emptyShelf.set(true)
                server.failShelf.set(false)
                var node: AccessibilityNodeInfo? = awaitLabel(retryLabel)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                awaitLabel(emptyLabel)
                assertTrue(server.seriesReads.get() > initialFailures)
                delay(2_500)
                val before = server.directory.get()
                status("ui-retry-published-empty")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KomgaSource
                assertTrue(source !== previous)
                assertEquals(namespace, source.shelfCacheNamespace())
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) } }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                assertEquals("Empty cache must survive Source and Activity recreation", before, server.directory.get())
                assertEquals(0, server.writes.get())
                assertEquals(0, server.unknown.get())
                status("new-source-activity-empty;directory=0;writes=0;unknown=0")
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
    fun ordinaryRefreshUpdatesOwnedChaptersAndRetainsCompleteCacheAfterFailure(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val downloads = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val oldDownloads = downloads.get()
        val hadDownloads = downloads.isSet()
        val profile = manager.add("komga", "Komga refresh ${UUID.randomUUID()}")
        val other = manager.add("komga", "Komga unchanged ${UUID.randomUUID()}")
        val server = Server()
        val wm = androidx.work.WorkManager.getInstance(context)
        val entries = mutableListOf<Long>()
        var workName: String? = null
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_refresh", value) })
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = try {
            withTimeout(15_000) {
                while (true) {
                    findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let {
                        return@withTimeout it
                    }
                    delay(50)
                }
                error("Unreachable")
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val labels = mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null || labels.size >= 30) return
                if (node.isVisibleToUser) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(120) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(120) }
                }
                for (index in 0 until node.childCount) collect(node.getChild(index))
            }
            collect(instrumentation.uiAutomation.rootInActiveWindow)
            throw AssertionError("Missing $label; labels=$labels; requests=${server.paths}", error)
        }
        try {
            downloaded.set(false)
            downloads.set(false)
            manager.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
            }
            selected.set(profile.id)
            val source = sources.get(profile.id) as KomgaSource
            workName = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
                profile.id,
                source.captureChapterOwner().key,
            )
            val name = workName
            entries += mangas.insertNetworkManga(
                listOf(profile.id, other.id).map { id ->
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = id,
                        url = "${server.root}/api/v1/series/1",
                        title = "Warm Komga series",
                        favorite = true,
                    )
                },
            ).map { it.id }
            suspend fun awaitWorker(count: Int) = try {
                withTimeout(20_000) {
                    while (true) {
                        val work = wm.getWorkInfosForUniqueWork(name).get()
                        if (work.size == count && work.all { it.state.isFinished }) {
                            assertTrue(work.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                            return@withTimeout
                        }
                        delay(50)
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val states = wm.getWorkInfosForUniqueWork(name).get().map { "${it.id}:${it.state}" }
                throw AssertionError("Expected $count workers; states=$states; requests=${server.paths}", error)
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) } }
                }
                awaitLabel("Warm Komga series")
                assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                assertEquals(0, server.chapterReads.get())
                pullRefresh(server, ::status)
                awaitWorker(1)
                assertEquals(1, chapters.getChapterByMangaId(entries[0]).size)
                assertTrue(chapters.getChapterByMangaId(entries[1]).isEmpty())
                delay(1_000)
                val complete = completeCache(context, source)
                val reads = server.seriesReads.get()
                status("pull-refresh-to-worker;owned-chapters=1;other=0")
                server.failDirectory.set(true)
                pullRefresh(server, ::status)
                awaitWorker(2)
                withTimeout(10_000) { while (server.seriesReads.get() == reads) delay(25) }
                awaitLabel("Warm Komga series")
                delay(1_000)
                assertEquals(complete, completeCache(context, source))
                assertEquals(1, chapters.getChapterByMangaId(entries[0]).size)
                assertTrue(chapters.getChapterByMangaId(entries[1]).isEmpty())
                assertTrue(server.failedReads.get() > 1)
                status("503-fallback;all-owned-cache-body-meta-equal;old-shelf-and-chapters-retained")
                server.failDirectory.set(false)
                server.chapterCount.set(2)
                server.seriesTitle = "Updated Komga fixture"
                server.additionalSeries.set(true)
                pullRefresh(server, ::status)
                awaitWorker(3)
                awaitLabel(server.seriesTitle)
                assertEquals(2, chapters.getChapterByMangaId(entries[0]).size)
                assertTrue(chapters.getChapterByMangaId(entries[1]).isEmpty())
                assertTrue(complete != completeCache(context, source))
                assertEquals(0, server.writes.get())
                assertEquals("Unknown: ${server.paths}", 0, server.unknown.get())
                status("retry-updates-shelf-and-two-chapters;other=0;writes=0")
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
                entries.forEach { notifications.cancel(it.hashCode()) }
                for (id in listOf(profile.id, other.id)) {
                    manager.remove(id).getOrThrow()
                    mangas.deleteMangaBySourceId(id)
                }
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                if (hadDownloads) downloads.set(oldDownloads) else downloads.delete()
                Injekt.get<ConnectionPreferences>().flush()
                server.close()
                status("owned-profile-sql-work-server-removed;preferences-restored")
            }
        }
    }

    @Test
    fun failedNonemptyMetadataRefreshRetainsCompleteCacheAcrossProcessRestart(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("shelfRestartPhase")
        org.junit.Assume.assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("shelfRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val file = java.io.File(context.cacheDir, "komga-shelf-restart-$run.json")
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
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
                    assertEquals("Komga shelf restart $run", it.name)
                    manager.remove(id).getOrThrow()
                }
                Injekt.get<MangaRepository>().deleteMangaBySourceId(id)
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
            val profile = manager.add("komga", "Komga shelf restart $run")
            saved = JSONObject().put("id", profile.id).put("pid", android.os.Process.myPid())
                .put("hadSelected", selected.isSet()).put("selected", selected.get())
                .put("hadDownloaded", downloaded.isSet()).put("downloaded", downloaded.get())
                .put("port", java.net.URI(server.root).port)
            save(saved)
        } else {
            saved = JSONObject(file.readText())
            assertTrue(saved.getInt("pid") != android.os.Process.myPid())
            server = Server(saved.getInt("port"))
                .apply { failDirectory.set(true) }
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
        try {
            val id = saved.getLong("id")
            if (phase == "prepare") {
                downloaded.set(false)
                manager.commitConfiguration(id) { draft ->
                    check(
                        draft.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                            .putString("Username", "fixture").putString("Password", "fixture").commit(),
                    )
                }
                selected.set(id)
            }
            val source = sources.get(id) as KomgaSource
            val owner = source.shelfCacheNamespace()
            val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, source.captureChapterOwner().key)
            saved.put("work", name)
            save(saved)
            val wm = androidx.work.WorkManager.getInstance(context)
            fun completeSnapshot(): String = org.json.JSONArray(
                completeCache(context, source).toSortedMap().map { (key, hashes) ->
                    org.json.JSONArray(listOf(key) + hashes)
                },
            ).toString()
            suspend fun awaitWorker(count: Int) = withTimeout(20_000) {
                while (true) {
                    val work = wm.getWorkInfosForUniqueWork(name).get()
                    if (work.size == count && work.all { it.state.isFinished }) {
                        assertTrue(work.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                        return@withTimeout
                    }
                    delay(50)
                }
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KomgaLibraryScreen(id, null, false)) } }
                }
                awaitLabel("Warm Komga series")
                if (phase == "prepare") {
                    val complete = completeSnapshot()
                    saved.put("snapshot", complete).put("owner", owner)
                    save(saved)
                    Injekt.get<ConnectionPreferences>().flush()
                    server.failDirectory.set(true)
                    pullRefresh(server, ::status)
                    awaitWorker(1)
                    withTimeout(10_000) { while (server.failedReads.get() < 2) delay(25) }
                    delay(750)
                    assertEquals(complete, completeSnapshot())
                    assertEquals(1, wm.getWorkInfosForUniqueWork(name).get().size)
                    awaitLabel("Warm Komga series")
                    status(
                        "prepared-failed-refresh;all-owned-cache-body-meta-retained;pid=" +
                            android.os.Process.myPid(),
                    )
                } else {
                    delay(2_000)
                    assertEquals(saved.getString("owner"), owner)
                    assertEquals(saved.getString("snapshot"), completeSnapshot())
                    assertEquals(0, server.directory.get())
                    assertEquals(1, wm.getWorkInfosForUniqueWork(name).get().size)
                    status("new-pid-warm-shelf;complete-body-meta-equal;directory=0;pid=" + android.os.Process.myPid())
                    server.failDirectory.set(false)
                    server.seriesTitle = "Recovered Komga fixture"
                    pullRefresh(server, ::status)
                    awaitLabel(server.seriesTitle)
                    awaitWorker(2)
                    assertTrue(server.directory.get() > 0)
                    assertEquals(1, server.seriesReads.get())
                    assertTrue(saved.getString("snapshot") != completeSnapshot())
                    status("new-pid-explicit-refresh-publishes-new-content;worker-succeeded")
                }
                assertEquals(0, server.writes.get())
                assertEquals("Unknown: ${server.paths}", 0, server.unknown.get())
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
        val bridge = checkNotNull(InstrumentationRegistry.getArguments().getString("komgaShelfBridge"))
        val url = URL(bridge)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == 20262)
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
        val profile = manager.add("komga", "Official empty shelf ${UUID.randomUUID()}")
        fun status(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", value) })
        try {
            downloaded.set(false)
            val setup = control()
            val address = setup.getString("server")
            require(address == "http://127.0.0.1:20261")
            manager.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", address).putString("AuthMode", "Credentials")
                        .putString("Username", setup.getString("username"))
                        .putString("Password", setup.getString("password")).commit(),
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as KomgaSource
            val namespace = source.shelfCacheNamespace()
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(context.stringResource(MR.strings.action_retry))
                assertEquals(
                    null,
                    findVisibleLabel(
                        instrumentation.uiAutomation.rootInActiveWindow,
                        context.stringResource(MR.strings.no_results_found),
                    ),
                )
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
                status("official-empty-response-persisted")
                val previous = source
                manager.commitConfiguration(profile.id) { }
                source = sources.get(profile.id) as KomgaSource
                assertTrue(source !== previous)
                assertEquals(namespace, source.shelfCacheNamespace())
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme { Navigator(KomgaLibraryScreen(profile.id, null, false)) }
                    }
                }
                awaitLabel(emptyLabel)
                delay(2_500)
                val warm = control("/state")
                assertEquals(empty.getInt("catalogue"), warm.getInt("catalogue"))
                assertEquals(0, warm.getInt("mutations"))
                status("official-new-source-activity-empty;directory=0;namespace-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    private suspend fun pullRefresh(server: Server, status: (String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(15_000) {
            while (true) {
                findVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }
        val bounds = android.graphics.Rect()
        instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(bounds)
        val card = android.graphics.Rect()
        awaitLabel("Warm Komga series").getBoundsInScreen(card)
        val x = card.exactCenterX()
        val from = bounds.top + bounds.height() * 0.25f
        val to = bounds.top + bounds.height() * 0.95f
        status("pull-window=$bounds;card=$card;directory-before=${server.directory.get()}")
        val started = android.os.SystemClock.uptimeMillis()
        for (step in 0..30) {
            val action = when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                30 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val event = android.view.MotionEvent.obtain(
                started,
                android.os.SystemClock.uptimeMillis(),
                action,
                x,
                from + (to - from) * step / 30,
                0,
            ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            delay(16)
        }
        delay(500)
        status(
            "pull-directory-after=${server.directory.get()};series=${server.seriesReads.get()};chapters=${server.chapterReads.get()}",
        )
    }
    private fun completeCache(context: android.content.Context, source: KomgaSource): Map<String, List<String>> {
        val namespace = source.shelfCacheNamespace()
        val directory = tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider.metadataCacheDir(context)
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val files = directory.listFiles().orEmpty().filter {
            it.extension == "meta" && it.readText().startsWith("$namespace:")
        }
        assertTrue("Expected complete metadata and shelf entries", files.size > 5)
        assertTrue(files.any { it.readText().contains("/api/v1/series/list") })
        return files.associate { file ->
            val body = java.io.File(directory, "${file.nameWithoutExtension}.body")
            file.name to listOf(hash(file.readBytes()), hash(body.readBytes()))
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

    private fun hasTitle(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.refresh()
        val title = "Warm Komga series"
        if (node.isVisibleToUser && (node.text?.toString() == title || node.contentDescription?.toString() == title)) {
            return true
        }
        return (0 until node.childCount).any { hasTitle(node.getChild(it)) }
    }

    private class Server(port: Int = 0) : AutoCloseable {
        private val listener = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val root = "http://127.0.0.1:${listener.localPort}"
        val directory = AtomicInteger()
        val seriesReads = AtomicInteger()
        val failShelf = AtomicBoolean()
        val failDirectory = AtomicBoolean()
        val failedReads = AtomicInteger()
        val chapterReads = AtomicInteger()
        val chapterCount = AtomicInteger(1)
        val additionalSeries = AtomicBoolean()
        val settingsLibraries = AtomicBoolean()
        val librarySelections = CopyOnWriteArrayList<Set<String>>()

        @Volatile var seriesTitle = "Warm Komga series"
        val emptyShelf = AtomicBoolean()
        val progress = AtomicInteger()
        val sse = AtomicInteger()
        val writes = AtomicInteger()
        val unknown = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        private val cover = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use(::respond)
                        } catch (_: IOException) {
                            // Source replacement can cancel a cover or event response.
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
            val method = request[0]
            val path = request[1].substringBefore('?')
            val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toInt() ?: 0
            require(length in 0..65_536)
            val requestBody = ByteArray(length).also { java.io.DataInputStream(input).readFully(it) }
            paths += "$method $path"
            val query = path in setOf("/api/v1/books/list", "/api/v1/series/list")
            if (method !in setOf("GET", "HEAD") && !(method == "POST" && query)) writes.incrementAndGet()
            var code = 200
            var media = "application/json"
            val empty = """{"content":[],"last":true,"totalElements":0,"totalPages":1,"number":0,"size":25}"""
            val bytes = when {
                path.endsWith("/thumbnail") -> {
                    media = "image/png"
                    cover
                }
                path == "/api/v1/sse/v1/events" -> {
                    sse.incrementAndGet()
                    media = "text/event-stream"
                    ": fixture\n\n".encodeToByteArray()
                }
                path == "/api/v1/books" -> {
                    progress.incrementAndGet()
                    empty.encodeToByteArray()
                }
                else -> {
                    directory.incrementAndGet()
                    if (failDirectory.get()) {
                        failedReads.incrementAndGet()
                        if (path == "/api/v1/series/list") seriesReads.incrementAndGet()
                        if (path == "/api/v1/series/1/books") chapterReads.incrementAndGet()
                        code = 503
                        "{}".encodeToByteArray()
                    } else {
                        when (path) {
                            "/api/v1/series/1/books" -> {
                                chapterReads.incrementAndGet()
                                val books = (1..chapterCount.get()).joinToString(",") { number ->
                                    """{"id":"book-$number","name":"Fixture book $number","fileLastModified":"2026-10-10T12:00:00","metadata":{"title":"Fixture book $number","numberSort":$number},"media":{"pagesCount":3}}"""
                                }
                                """{"content":[$books],"last":true,"totalElements":${chapterCount.get()},"totalPages":1,"number":0,"size":25}"""
                            }
                            "/api/v1/libraries" -> if (settingsLibraries.get()) {
                                """[{"id":"1","name":"First fixture library"},{"id":"2","name":"Second fixture library"}]"""
                            } else {
                                """[{"id":"1","name":"Fixture library"}]"""
                            }
                            "/api/v1/client-settings/user/list" -> "{}"
                            "/api/v1/collections", "/api/v1/books/list" -> empty
                            "/api/v1/genres", "/api/v1/tags", "/api/v1/publishers", "/api/v1/authors" -> "[]"
                            "/api/v1/series/list" -> {
                                seriesReads.incrementAndGet()
                                when {
                                    settingsLibraries.get() -> {
                                        val libraries = mutableSetOf<String>()
                                        fun collect(value: Any?) {
                                            when (value) {
                                                is JSONObject -> {
                                                    value.optJSONObject("libraryId")?.let {
                                                        assertEquals("is", it.getString("operator"))
                                                        libraries += it.getString("value")
                                                    }
                                                    value.keys().forEach { collect(value.get(it)) }
                                                }
                                                is org.json.JSONArray -> for (i in 0 until value.length()) {
                                                    collect(
                                                        value.get(i),
                                                    )
                                                }
                                            }
                                        }
                                        collect(JSONObject(requestBody.decodeToString()).getJSONObject("condition"))
                                        librarySelections += libraries.toSet()
                                        val ids = listOf("1", "2").filter { libraries.isEmpty() || it in libraries }
                                        val content = ids.joinToString(",") { id ->
                                            val title = if (id ==
                                                "1"
                                            ) {
                                                "First library series"
                                            } else {
                                                "Second library series"
                                            }
                                            """{"id":"$id","libraryId":"$id","name":"$title","booksCount":1,
                                            "fileLastModified":"2026-01-01T00:00:00Z",
                                            "metadata":{"title":"$title","titleSort":"$title"}}
                                            """.trimIndent()
                                        }
                                        """{"content":[$content],"last":true,"totalElements":${ids.size},
                                        "totalPages":1,"number":0,"size":25}
                                        """.trimIndent()
                                    }
                                    failShelf.get() -> {
                                        code = 503
                                        "{}"
                                    }
                                    emptyShelf.get() -> empty
                                    else -> """
                            {"content":[{"id":"1","libraryId":"1","name":"Warm Komga series",
                            "created":"2026-01-01T00:00:00Z","lastModified":"2026-01-01T00:00:00Z",
                            "fileLastModified":"2026-01-01T00:00:00Z","booksCount":1,
                            "metadata":{"title":"Warm Komga series","titleSort":"Warm Komga series"}}],
                            "last":true,"totalElements":1,"totalPages":1,"number":0,"size":25}
                                    """.trimIndent().replace("Warm Komga series", seriesTitle).let { body ->
                                        if (!additionalSeries.get()) {
                                            body
                                        } else {
                                            JSONObject(body).apply {
                                                val content = getJSONArray("content")
                                                content.put(
                                                    JSONObject(content.getJSONObject(0).toString()).put("id", "2"),
                                                )
                                                put("totalElements", 2)
                                            }.toString()
                                        }
                                    }
                                }
                            }
                            else -> {
                                unknown.incrementAndGet()
                                "{}"
                            }
                        }.encodeToByteArray()
                    }
                }
            }
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 $code Fixture\r\nContent-Type: $media\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).encodeToByteArray(),
                )
                write(bytes)
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
}
