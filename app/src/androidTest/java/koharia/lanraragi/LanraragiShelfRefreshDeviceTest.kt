package koharia.lanraragi

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LanraragiShelfRefreshDeviceTest {
    @Test
    fun ordinaryRefreshSchedulesOwnedChaptersAndRetainsCompleteShelfAfterFailure(): Unit =
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertEquals("app.koharia.dev.devicefixture", context.packageName)
            val manager = Injekt.get<ConnectionProfileManager>()
            val sources = Injekt.get<SourceManager>()
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
            val profile = manager.add(LanraragiConnectionProvider.ID, "Lanraragi refresh ${UUID.randomUUID()}")
            val other = manager.add(LanraragiConnectionProvider.ID, "Lanraragi unchanged ${UUID.randomUUID()}")
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
                    LanraragiPreferences(profile.id, draft).save(server.address, "fixture-key")
                }
                selected.set(profile.id)
                val source = sources.get(profile.id) as LanraragiSource
                workName = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
                    source.id,
                    source.captureChapterOwner().key,
                )
                val name = workName
                entryIds += mangas.insertNetworkManga(
                    listOf(profile.id, other.id).map { id ->
                        Manga.create().copy(
                            source = id,
                            url = "/lanraragi/$id/tank/TANK_1234567890",
                            title = "Fixture collection",
                            favorite = true,
                        )
                    },
                ).map { it.id }
                suspend fun catalogue() = completeSnapshot(profile.id)
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
                            TachiyomiPreviewTheme { Navigator(LanraragiLibraryScreen(profile.id, null, false)) }
                        }
                    }
                    awaitLabel("Fixture collection")
                    withTimeout(15_000) { while (source.status.value.running) delay(50) }
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    refresh()
                    val firstWork = awaitWorker(1)
                    assertEquals(3, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    status("overflow-refresh-to-worker;owned-chapters=3;other-chapters=0")
                    val complete = catalogue()
                    val reads = server.failedReads.get()
                    server.failShelf.set(true)
                    refresh()
                    withTimeout(15_000) {
                        while (source.status.value.running || source.status.value.error == null) delay(50)
                    }
                    assertEquals(503, (source.status.value.error as LanraragiException).status)
                    delay(1_500)
                    assertEquals(reads + 1, server.failedReads.get())
                    assertEquals(complete, catalogue())
                    assertEquals(firstWork, wm.getWorkInfosForUniqueWork(name).get().map { it.id }.toSet())
                    awaitLabel("Fixture collection")
                    status("failed-refresh-full-shelf-generation-equal;no-worker;old-content-visible")
                    server.failShelf.set(false)
                    server.chapterCount.set(4)
                    refresh()
                    awaitWorker(2)
                    assertEquals(4, chapters.getChapterByMangaId(entryIds[0]).size)
                    assertTrue(chapters.getChapterByMangaId(entryIds[1]).isEmpty())
                    assertTrue(complete != catalogue())
                    assertEquals(0, server.mutations.get())
                    assertEquals(0, server.unexpected.get())
                    status("second-explicit-refresh-to-worker;owned-chapters=4;other=0;business-writes=0")
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
        val file = java.io.File(context.cacheDir, "lanraragi-shelf-restart-$run.json")
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
                    assertEquals("Lanraragi shelf restart $run", it.name)
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
        val server: DirectoryServer
        if (phase == "prepare") {
            check(!file.exists())
            server = DirectoryServer()
            val profile = manager.add(LanraragiConnectionProvider.ID, "Lanraragi shelf restart $run")
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
                    LanraragiPreferences(id, draft).save(server.address, "fixture-key")
                }
                selected.set(id)
            }
            val source = sources.get(id) as LanraragiSource
            val owner = source.captureChapterOwner().key
            val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, source.captureChapterOwner().key)
            saved.put("work", name)
            save(saved)
            val wm = androidx.work.WorkManager.getInstance(context)
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiPreviewTheme { Navigator(LanraragiLibraryScreen(id, null, false)) } }
                }
                awaitLabel("Fixture collection")
                if (phase == "prepare") {
                    withTimeout(15_000) { while (source.status.value.running) delay(50) }
                    saved.put("owner", owner)
                    save(saved)
                    Injekt.get<ConnectionPreferences>().flush()
                    val before = completeSnapshot(id)
                    server.failShelf.set(true)
                    refresh()
                    withTimeout(15_000) {
                        while (source.status.value.running || source.status.value.error == null) delay(50)
                    }
                    assertEquals(503, (source.status.value.error as LanraragiException).status)
                    assertEquals(before, completeSnapshot(id))
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    awaitLabel("Fixture collection")
                    saved.put("snapshot", completeSnapshot(id))
                    save(saved)
                    status(
                        "prepared-failed-refresh;full-nonempty-sql-generation-retained;pid=" +
                            android.os.Process.myPid(),
                    )
                } else {
                    delay(2_000)
                    assertEquals(saved.getString("owner"), owner)
                    assertEquals(saved.getString("snapshot"), completeSnapshot(id))
                    assertEquals(0, server.directoryReads.get())
                    assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                    status("new-pid-warm-shelf;complete-generation-equal;directory=0;pid=" + android.os.Process.myPid())
                    server.failShelf.set(false)
                    server.shelfTitle = "Recovered Lanraragi fixture"
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
                    assertTrue(server.directoryReads.get() > 0)
                    assertEquals(0, server.failedReads.get())
                    assertTrue(saved.getString("snapshot") != completeSnapshot(id))
                    status("new-pid-explicit-refresh-publishes-new-content;worker-succeeded")
                }
                assertEquals(0, server.mutations.get())
                assertEquals(0, server.unexpected.get())
            }
        } finally {
            server.close()
        }
    }

    private fun completeSnapshot(id: Long): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val result = org.json.JSONArray()
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("tachiyomi.db").absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            for ((table, columns, order) in listOf(
                Triple("lanraragi_sync", "generation,completed_at", "generation"),
                Triple("lanraragi_catalog", "generation,resource_id,payload", "generation,resource_id"),
                Triple("lanraragi_members", "generation,parent_id,member_id,position", "generation,parent_id,position"),
            )) {
                val rows = org.json.JSONArray()
                db.rawQuery("SELECT $columns FROM $table WHERE connection_id=? ORDER BY $order", arrayOf("$id"))
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            rows.put(org.json.JSONArray((0 until cursor.columnCount).map { cursor.getString(it) }))
                        }
                    }
                assertTrue("$table must have a complete successful generation", rows.length() > 0)
                result.put(org.json.JSONArray(listOf(table, rows)))
            }
        }
        return result.toString()
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
        val address get() = "http://127.0.0.1:${listener.localPort}/"
        val requestCount = AtomicInteger()
        val failedReads = AtomicInteger()
        val directoryReads = AtomicInteger()
        val chapterCount = AtomicInteger(3)

        @Volatile var shelfTitle = "Fixture collection"
        val mutations = AtomicInteger()
        val unexpected = AtomicInteger()
        val failShelf = AtomicBoolean()

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
            require(body.isEmpty())
            requestCount.incrementAndGet()
            val path = request[1].substringBefore('?')
            var code = 200
            val response = when {
                request[0] !in setOf("GET", "HEAD") -> {
                    mutations.incrementAndGet()
                    code = 403
                    "{}"
                }
                path.endsWith("/thumbnail") -> {
                    code = 404
                    "{}"
                }
                path == "/api/info" -> {
                    directoryReads.incrementAndGet()
                    """{"version":"0.9.80","archives_per_page":100,"server_tracks_progress":true,
                    "authenticated_progress":true,"nofun_mode":true}
                    """.trimIndent()
                }
                path == "/api/archives/untagged" -> {
                    directoryReads.incrementAndGet()
                    "[]"
                }
                path == "/api/archives" -> {
                    (1..5).joinToString(",", "[", "]") { index ->
                        val id = index.toString(16).padStart(40, '0')
                        """{"arcid":"$id","title":"Fixture book $index","tags":"date_added:$index",
                        "pagecount":3,"progress":0,"lastreadtime":0,"isnew":false}
                        """.trimIndent()
                    }
                }
                path == "/api/tankoubons" -> {
                    directoryReads.incrementAndGet()
                    """{"result":[{"id":"TANK_1234567890"}],"total":1,"filtered":1}"""
                }
                path == "/api/tankoubons/TANK_1234567890/full" -> {
                    directoryReads.incrementAndGet()
                    val members = (1..chapterCount.get()).joinToString(",") {
                        "\"${it.toString(16).padStart(40, '0')}\""
                    }
                    """{"result":{"id":"TANK_1234567890","name":"$shelfTitle","archives":[$members]},
                    "total":${chapterCount.get()},"filtered":${chapterCount.get()}}
                    """.trimIndent()
                }
                path == "/api/categories" -> {
                    directoryReads.incrementAndGet()
                    if (failShelf.get()) {
                        failedReads.incrementAndGet()
                        code = 503
                        "{}"
                    } else {
                        """[{"id":"SET_1234567890","name":"Fixture category","archives":[],"search":""}]"""
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
}
