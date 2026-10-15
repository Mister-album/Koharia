package koharia.source.local

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.LibraryContentScope
import koharia.connection.SharedAppPreferences
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalNativeRefreshDeviceTest {
    @Test
    fun ordinaryScanUpdatesOwnedFavoriteWithoutDownloadingNativeFiles(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val shared = Injekt.get<SharedAppPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val wm = WorkManager.getInstance(context)
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val had = preference.isSet()
            val old = preference.get()
            restores += { if (had) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.cacheDir, "native-refresh-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && !root.exists() && root.mkdirs())
        val owned = mutableListOf<Long>()
        val workNames = mutableListOf<String>()
        val seed = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("001.png"))
                val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                try {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, zip))
                } finally {
                    image.recycle()
                }
                zip.closeEntry()
            }
        }.toByteArray()
        try {
            replace(connections.activeConnectionId, ConnectionPreferences.LOCAL_CONNECTION_ID)
            replace(Injekt.get<eu.kanade.domain.base.BasePreferences>().shownOnboardingFlow, true)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.downloadPreferences().downloadNewChapters, true)
            val sources = (0..1).map { index ->
                val profile = profiles.add("local-folder", "Native refresh ${UUID.randomUUID()}")
                owned += profile.id
                val directory = File(root, "owner-$index").apply { check(mkdir()) }
                val series = File(directory, "Original").apply { check(mkdir()) }
                File(series, "Chapter 1.cbz").writeBytes(seed)
                profiles.commitConfiguration(profile.id) { draft ->
                    LocalLibraryPreferences(profile.id, Json, draft).setConfig(
                        LocalLibraryConfig(
                            setupCompleted = true,
                            roots = listOf(
                                LocalLibraryRootConfig(
                                    id = "root",
                                    treeUri = directory.toURI().toString(),
                                    contentType = LocalLibraryContentType.COMICS,
                                    bookshelfId = "shelf",
                                ),
                            ),
                            bookshelves = listOf(
                                LocalBookshelf(
                                    "shelf",
                                    "Fixture shelf",
                                    LocalLibraryContentType.COMICS,
                                    LocalLibraryOrganizationMode.SERIES,
                                ),
                            ),
                        ),
                    )
                }
                (Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource).also {
                    it.refreshLibrary().getOrThrow()
                    val manga = mangas.getMangaBySourceId(profile.id).single()
                    mangas.update(MangaUpdate(manga.id, favorite = true))
                    workNames += LibraryUpdateJob.workName(it.id, it.captureChapterOwner().key)
                    assertFalse(it.mangaBehavior.allowsChapterDownloads)
                }
            }
            val source = sources.first()
            connections.activeConnectionId.set(source.id)
            val manga = mangas.getMangaBySourceId(source.id).single()
            val neighbor = mangas.getMangaBySourceId(sources.last().id).single()
            val neighborBefore = chapters.getChapterByMangaId(neighbor.id)
            suspend fun label(): AccessibilityNodeInfo = withTimeout(20_000) {
                while (true) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    findLabel(instrumentation.uiAutomation.rootInActiveWindow, "Original")?.let {
                        return@withTimeout it
                    }
                    delay(50)
                }
                error("Unreachable")
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(LocalLibraryScreen(source.id, LibraryContentScope.ALL, null, false))
                        }
                    }
                }
                label()
                val before = wm.getWorkInfosForUniqueWork(workNames.first()).get().map { it.id }.toSet()
                File(root, "owner-0/Original/Chapter 2.cbz").writeBytes(seed)
                val bounds = Rect()
                instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(bounds)
                val card = Rect()
                label().getBoundsInScreen(card)
                val start = android.os.SystemClock.uptimeMillis()
                for (step in 0..30) {
                    val event = android.view.MotionEvent.obtain(
                        start,
                        android.os.SystemClock.uptimeMillis(),
                        when (step) {
                            0 -> android.view.MotionEvent.ACTION_DOWN
                            30 -> android.view.MotionEvent.ACTION_UP
                            else -> android.view.MotionEvent.ACTION_MOVE
                        },
                        card.exactCenterX(),
                        bounds.top + bounds.height() * (0.25f + 0.7f * step / 30),
                        0,
                    ).apply { this.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    try {
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                    } finally {
                        event.recycle()
                    }
                    delay(16)
                }
                withTimeout(30_000) {
                    while (true) {
                        val added = wm.getWorkInfosForUniqueWork(workNames.first()).get().filter { it.id !in before }
                        if (added.isNotEmpty() && added.all { it.state.isFinished }) {
                            assertEquals(1, added.size)
                            assertTrue(added.all { it.state == WorkInfo.State.SUCCEEDED })
                            break
                        }
                        delay(50)
                    }
                }
                assertEquals(2, chapters.getChapterByMangaId(manga.id).size)
                assertEquals(neighborBefore, chapters.getChapterByMangaId(neighbor.id))
                assertTrue(wm.getWorkInfosForUniqueWork(workNames.last()).get().isEmpty())
                assertTrue(downloads.queueState.value.none { it.source.id in owned })
                for (file in root.walkTopDown().filter { it.isFile }) assertTrue(seed.contentEquals(file.readBytes()))
                assertEquals(3, root.walkTopDown().count { it.isFile })
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "native_refresh",
                            "actual-pull-worker;owned-two-chapters;neighbor-unchanged;native-no-download",
                        )
                    },
                )
            }
        } finally {
            workNames.forEach { name ->
                wm.cancelUniqueWork(name).result.get()
                withTimeout(10_000) {
                    while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(25)
                }
            }
            owned.forEach { id ->
                mangas.getMangaBySourceId(id).forEach {
                    context.getSystemService(android.app.NotificationManager::class.java).cancel(it.id.hashCode())
                }
                profiles.remove(id).getOrThrow()
            }
            restores.asReversed().forEach { it() }
            connections.flush()
            check(root.parentFile == context.cacheDir.canonicalFile && root.deleteRecursively())
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("native_cleanup", "owned-profile-work-sql-files-removed;preferences-restored")
                },
            )
        }
    }

    private fun findLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            return node
        }
        for (index in 0 until node.childCount) findLabel(node.getChild(index), label)?.let { return it }
        return null
    }
}
