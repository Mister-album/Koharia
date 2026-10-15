package koharia.kavita

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
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.ui.KavitaLibraryScreen
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode
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
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class KavitaDownloadedShelfDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun downloadedShelfUiChangesMediaSortAndRespondsToDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val repository = Injekt.get<KavitaRepository>()
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
        val profile = profiles.add("kavita", "Downloaded UI ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        val files = mutableMapOf<Long, UniFile>()
        var source: KavitaSource? = null
        val server = UnexpectedRequestServer()
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(Injekt.get<BasePreferences>().downloadedOnly, true)
            replace(Injekt.get<SourcePreferences>().sourceDisplayMode, LibraryDisplayMode.List)
            connections.activeConnectionId.set(profile.id)
            KavitaPreferences(profile.id).save(server.address, "fixture-key", server.account)
            val active = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is KavitaSource) delay(25)
                (Injekt.get<SourceManager>().get(profile.id) as KavitaSource).also { it.reload() }
            }
            source = active
            check(provider.findSourceDirs(active).isEmpty())
            val session = active.session()
            val titles = listOf("UI download A", "UI download B", "UI download C")
            val nameOrder = listOf(titles[2], titles[0], titles[1])
            repository.putCache(
                profile.id,
                session.accountKey,
                "libraries",
                KavitaCacheEntry(
                    "data",
                    Json.encodeToString(listOf(KavitaLibrary(10, "Media A"), KavitaLibrary(20, "Media B"))),
                    1,
                ),
            )
            val seed = archiveBytes()
            for (index in titles.indices.reversed()) {
                val title = titles[index]
                val remote = KavitaSeries(
                    id = index + 1L,
                    libraryId = if (index == 1) 20 else 10,
                    name = title,
                    sortName = listOf("B", "C", "A")[index],
                    created = listOf("2025-02-01", "2025-01-01", "2025-03-01")[index] + "T00:00:00",
                )
                repository.putCache(
                    profile.id,
                    session.accountKey,
                    "series/${remote.id}",
                    KavitaCacheEntry("data", Json.encodeToString(remote), 1),
                )
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = session.identity.series(remote.id),
                            title = title,
                            memo = JsonObject(mapOf("kavitaLibraryId" to JsonPrimitive(remote.libraryId))),
                        ),
                    ),
                ).single()
                stored += manga
                check(provider.findMangaDirs(title, active).isEmpty())
                val directory = provider.getMangaDir(title, active).getOrThrow()
                check(directory.parentFile?.name == active.downloadDirectoryName())
                val file = checkNotNull(directory.createFile("fixture.cbz"))
                file.openOutputStream().use { it.write(seed) }
                files[manga.id] = file
                cache.addChapter("fixture.cbz", directory, manga)
                assertEquals(1, downloads.getDownloadCount(manga))
            }
            repository.putCache(
                profile.id,
                "foreign-account",
                "series/1",
                KavitaCacheEntry(
                    "data",
                    Json.encodeToString(KavitaSeries(id = 1, libraryId = 20, name = "Foreign")),
                    2,
                ),
            )
            stored.sortBy { it.title }
            val updatedFilter = active.filter(listOf(10, 20), "", "3 desc")
            val updatedGroup = "shelf/" +
                ("Series/all-v2" + session.api.json.encodeToString(updatedFilter)).encodeUtf8().sha256().hex()
            repository.putCache(
                profile.id,
                session.accountKey,
                updatedGroup,
                KavitaCacheEntry(
                    "1",
                    session.api.json.encodeToString(
                        KavitaSeriesPage(
                            listOf(2L, 3L, 1L).map {
                                KavitaSeries(it)
                            },
                            false,
                        ),
                    ),
                    1,
                ),
            )
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { TachiyomiTheme { Navigator(KavitaLibraryScreen(profile.id, null, false)) } }
                }
                awaitTitles(nameOrder, titles)
                click(awaitLabel("Media A"))
                awaitTitles(listOf(titles[2], titles[0]), titles)
                assertEquals(10L, active.preferences.mediaId)
                click(awaitLabel("Media B"))
                awaitTitles(listOf(titles[1]), titles)
                assertEquals(20L, active.preferences.mediaId)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(nameOrder, titles)
                click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                click(awaitLabel(context.stringResource(MR.strings.kavita_descending)))
                click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                awaitTitles(nameOrder.reversed(), titles)
                suspend fun selectSort(current: String, next: String) {
                    click(awaitLabel(context.stringResource(MR.strings.action_filter)))
                    click(awaitLabel(current))
                    click(awaitLabel(next))
                    click(awaitLabel(context.stringResource(MR.strings.action_filter), textOnly = true))
                }
                selectSort(
                    context.stringResource(MR.strings.kavita_sort_name),
                    context.stringResource(MR.strings.kavita_sort_created),
                )
                awaitTitles(listOf(titles[2], titles[0], titles[1]), titles)
                selectSort(
                    context.stringResource(MR.strings.kavita_sort_created),
                    context.stringResource(MR.strings.kavita_sort_updated),
                )
                awaitTitles(listOf(titles[1], titles[2], titles[0]), titles)
                selectSort(
                    context.stringResource(MR.strings.kavita_sort_updated),
                    context.stringResource(MR.strings.kavita_sort_name),
                )
                awaitTitles(nameOrder.reversed(), titles)
                click(awaitLabel("Media A"))
                awaitTitles(listOf(titles[0], titles[2]), titles)

                // Exercise the production deletion event while the actual shelf remains mounted.
                downloads.deleteManga(stored[2], active, removeQueued = false)
                awaitTitles(listOf(titles[0]), titles)
                assertTrue(!files.getValue(stored[2].id).exists())
                assertEquals(0, downloads.getDownloadCount(stored[2]))
                click(awaitLabel("Media B"))
                awaitTitles(listOf(titles[1]), titles)
                click(awaitLabel(context.stringResource(MR.strings.all)))
                awaitTitles(listOf(titles[1], titles[0]), titles)
                for (manga in stored.take(2)) {
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
                            "media=10,20,all;sort=server-name,created,cached-updated;delete=production-event",
                        )
                        putInt("remote_requests", server.requests.get())
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    source?.let { active ->
                        for (manga in stored) {
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
                        active.close()
                    }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    repository.removeConnection(profile.id)
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

    private suspend fun awaitLabel(label: String, textOnly: Boolean = false): AccessibilityNodeInfo = withTimeout(
        15_000,
    ) {
        while (true) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).filter {
                it.isVisibleToUser &&
                    (it.text?.toString() == label || (!textOnly && it.contentDescription?.toString() == label))
            }.minByOrNull { Rect().also(it::getBoundsInScreen).top }?.let { return@withTimeout it }
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
        val account = KavitaAccount(id = 129, username = "fixture", token = "fixture-token", kavitaVersion = "0.8.8")
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            it.soTimeout = 5_000
                            val path = it.getInputStream().bufferedReader().readLine().split(
                                ' ',
                            )[1].substringBefore('?')
                            val (status, body) = when (path) {
                                "/api/Plugin/authenticate", "/api/Account/refresh-account" ->
                                    "200 OK" to Json.encodeToString(account)
                                "/hubs/messages/negotiate" -> "404 Not Found" to ""
                                else -> {
                                    requests.incrementAndGet()
                                    "503 Fixture" to ""
                                }
                            }
                            val bytes = body.toByteArray()
                            it.getOutputStream().write(
                                (
                                    "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray() + bytes,
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
