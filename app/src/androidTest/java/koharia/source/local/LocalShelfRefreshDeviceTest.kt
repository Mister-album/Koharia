package koharia.source.local

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.LibraryContentScope
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL

class LocalShelfRefreshDeviceTest {
    @Test
    fun networkRefreshFailureRetainsCompleteIndexAcrossProcessRestart(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("shelfRestartPhase")
        org.junit.Assume.assumeTrue(phase in setOf("prepare", "recover", "cleanup"))
        val run = checkNotNull(args.getString("shelfRestartRun"))
        require(run.matches(Regex("[0-9]+")))
        val mode = LibraryStorageMode.valueOf(checkNotNull(args.getString("shelfMode")))
        require(mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val file = java.io.File(context.cacheDir, "network-shelf-restart-$mode-$run.json")
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val progress = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>().showLibraryReadProgress
        val autoDownload = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters
        fun status(
            value: String,
        ) = instrumentation.sendStatus(2, Bundle().apply { putString("network_shelf", "$mode;$value") })
        fun save(value: JSONObject) = file.outputStream().use {
            it.write(value.toString().toByteArray())
            it.fd.sync()
        }
        fun checkpoint(name: String): JSONObject {
            val connection = URL(
                "http://127.0.0.1:21463/checkpoint?mode=$mode&phase=$name",
            ).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        val wm = androidx.work.WorkManager.getInstance(context)
        if (phase == "cleanup") {
            if (file.exists()) {
                val saved = JSONObject(file.readText())
                val id = saved.getLong("id")
                saved.optString("work").takeIf { it.isNotEmpty() }?.let { name ->
                    wm.cancelUniqueWork(name).result.get()
                    withTimeout(10_000) {
                        while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(25)
                    }
                }
                Injekt.get<LocalLibraryRefreshTasks>().cancel(id)
                manager.profiles().firstOrNull { it.id == id }?.let {
                    assertEquals("Network shelf $mode $run", it.name)
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
                if (saved.getBoolean("hadProgress")) progress.set(saved.getBoolean("progress")) else progress.delete()
                if (saved.getBoolean(
                        "hadAutoDownload",
                    )
                ) {
                    autoDownload.set(saved.getBoolean("autoDownload"))
                } else {
                    autoDownload.delete()
                }
                Injekt.get<ConnectionPreferences>().flush()
                check(file.delete())
            }
            status("owned-profile-index-sql-work-checkpoint-removed;preferences-restored")
            return@runBlocking
        }
        val saved = if (phase == "prepare") {
            check(!file.exists())
            val profile = manager.add("local-folder", "Network shelf $mode $run")
            JSONObject().put("id", profile.id).put("pid", android.os.Process.myPid())
                .put("hadSelected", selected.isSet()).put("selected", selected.get())
                .put("hadDownloaded", downloaded.isSet()).put("downloaded", downloaded.get())
                .put("hadProgress", progress.isSet()).put("progress", progress.get())
                .put("hadAutoDownload", autoDownload.isSet()).put("autoDownload", autoDownload.get()).also(::save)
        } else {
            JSONObject(file.readText()).also { assertTrue(it.getInt("pid") != android.os.Process.myPid()) }
        }
        val id = saved.getLong("id")
        if (phase == "prepare") {
            downloaded.set(false)
            progress.set(false)
            autoDownload.set(false)
            checkpoint("allow")
            manager.commitConfiguration(id) { draft ->
                val network = NetworkStoragePreferences(id, draft)
                network.save(
                    NetworkStorageConfiguration(
                        mode = mode,
                        address = if (mode ==
                            LibraryStorageMode.WEBDAV
                        ) {
                            "http://127.0.0.1:21461/"
                        } else {
                            "smb://127.0.0.1:21462/fixture/"
                        },
                        rootIdentity = "7ca12ea3-261e-4159-9c96-3f96385d4a73",
                    ),
                    "root",
                    "fixture-214",
                )
                LocalLibraryPreferences(id, Json, draft).setConfig(
                    LocalLibraryConfig(
                        setupCompleted = true,
                        roots = listOf(
                            LocalLibraryRootConfig(
                                id = "shelf-root",
                                treeUri = DocumentsContract.buildDocumentUri(
                                    NetworkStorageRuntime.authority(context),
                                    "$id|${network.account}|Books",
                                ).toString(),
                                contentType = LocalLibraryContentType.COMICS,
                                bookshelfId = "fixture-shelf",
                            ),
                        ),
                        bookshelves = listOf(
                            LocalBookshelf(
                                "fixture-shelf",
                                "Fixture shelf",
                                LocalLibraryContentType.COMICS,
                                LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                            ),
                        ),
                    ),
                )
            }
            selected.set(id)
        }
        val source = sources.get(id) as LocalFolderSource
        val preferences = LocalLibraryPreferences(id, Json)
        val owner = source.captureChapterOwner().key
        val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(id, owner)
        saved.put("work", name)
        save(saved)
        suspend fun awaitWorker(count: Int) = withTimeout(30_000) {
            while (true) {
                val work = wm.getWorkInfosForUniqueWork(name).get()
                if (work.size == count && work.all { it.state.isFinished }) {
                    assertTrue(work.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                    return@withTimeout work.map { it.id.toString() }.sorted()
                }
                delay(50)
            }
            error("Unreachable")
        }
        suspend fun awaitLabel(label: String): AccessibilityNodeInfo = withTimeout(20_000) {
            while (true) {
                findLabel(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return@withTimeout it }
                delay(50)
            }
            error("Unreachable")
        }
        suspend fun refresh() {
            val bounds = android.graphics.Rect()
            instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(bounds)
            val card = android.graphics.Rect()
            awaitLabel("Original").getBoundsInScreen(card)
            val x = card.exactCenterX()
            val from = bounds.top + bounds.height() * 0.25f
            val to = bounds.top + bounds.height() * 0.95f
            status("ordinary-pull-window=$bounds;card=$card")
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
                ).apply { this.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
                delay(16)
            }
            delay(500)
        }
        fun snapshot() = Json.encodeToString(preferences.getIndex())
        if (phase == "prepare") {
            source.refreshLibrary().getOrThrow()
            assertTrue(preferences.getIndex().items.any { it.relativePath == "Original.cbz" })
            assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
        }
        val beforeWarm = checkpoint("before-warm")
        FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    TachiyomiTheme { Navigator(LocalLibraryScreen(id, LibraryContentScope.ALL, null, false)) }
                }
            }
            awaitLabel("Original")
            if (phase == "prepare") {
                refresh()
                val work = awaitWorker(1)
                status("ordinary-ui-scan-to-worker;complete-nonempty-index")
                val complete = snapshot()
                checkpoint("fail")
                refresh()
                val tasks = Injekt.get<LocalLibraryRefreshTasks>()
                withTimeout(30_000) { while (id in tasks.activeIds.value || tasks.errors.value[id] == null) delay(50) }
                assertEquals(complete, snapshot())
                assertEquals(work, wm.getWorkInfosForUniqueWork(name).get().map { it.id.toString() }.sorted())
                awaitLabel("Original")
                assertTrue(checkpoint("failed").getInt("blocked") > 0)
                saved.put("owner", owner).put("snapshot", complete).put("workIds", org.json.JSONArray(work))
                save(saved)
                Injekt.get<ConnectionPreferences>().flush()
                status(
                    "failed-scan-full-index-equal;no-new-worker;old-content-visible;pid=" + android.os.Process.myPid(),
                )
            } else {
                delay(2_000)
                assertEquals(saved.getString("owner"), owner)
                assertEquals(saved.getString("snapshot"), snapshot())
                assertEquals(saved.getJSONArray("workIds").toString(), org.json.JSONArray(awaitWorker(1)).toString())
                assertEquals(beforeWarm.getInt("catalogue"), checkpoint("warm").getInt("catalogue"))
                status("new-pid-warm;full-index-equal;directory=0;pid=" + android.os.Process.myPid())
                checkpoint("add")
                refresh()
                awaitLabel("Recovered")
                awaitWorker(2)
                assertTrue(preferences.getIndex().items.any { it.relativePath == "Recovered.cbz" })
                assertTrue(saved.getString("snapshot") != snapshot())
                assertTrue(checkpoint("recovered").getInt("catalogue") > beforeWarm.getInt("catalogue"))
                status("new-pid-explicit-scan-new-content;second-worker-succeeded")
            }
            assertEquals(0, checkpoint("verified").getInt("mutations"))
        }
    }

    private fun findLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.refresh()
        if (node.isVisibleToUser &&
            (node.text?.toString() == label || node.contentDescription?.toString() == label)
        ) {
            return node
        }
        for (i in 0 until node.childCount) findLabel(node.getChild(i), label)?.let { return it }
        return null
    }
}
