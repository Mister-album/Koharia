package koharia.database

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import koharia.source.local.defaultBookshelfId
import koharia.source.local.withInitialBookshelves
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalReaderDatabaseStartupDeviceTest {
    @Test
    fun localCbzPersistsMissingPageCountAndDisplaysFirstImage(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previousActive = connections.activeConnectionId.get()
        val profile = profiles.add("local-folder", "Database startup fixture")
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val session = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "database-reader-$session").apply { mkdirs() }
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        try {
            val base = Injekt.get<BasePreferences>()
            val reader = Injekt.get<SharedAppPreferences>().readerPreferences()
            replace(base.shownOnboardingFlow, true)
            replace(base.incognitoMode, false)
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) == null) delay(25)
            }
            val comic = File(File(directory, "Series").apply { mkdirs() }, "fixture.cbz")
            ZipOutputStream(comic.outputStream()).use { zip ->
                repeat(3) { page ->
                    val bitmap = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.rgb(40 + page * 50, 80, 140))
                        zip.putNextEntry(ZipEntry("$page.png"))
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                        zip.closeEntry()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            LocalLibraryPreferences(profile.id, Json).setConfig(
                config.copy(
                    setupCompleted = true,
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = session,
                            treeUri = directory.toURI().toString(),
                            contentType = LocalLibraryContentType.COMICS,
                            bookshelfId = config.defaultBookshelfId(LocalLibraryContentType.COMICS),
                        ),
                    ),
                ),
            )
            val source = Injekt.get<SourceManager>().get(profile.id) as LocalFolderSource
            withTimeout(15_000) { source.refreshLibrary().getOrThrow() }
            val manga = mangas.getMangaBySourceId(profile.id).single()
            Injekt.get<SyncChaptersWithSource>().await(source.getChapterList(manga.toSManga()), manga, source)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            chapters.update(ChapterUpdate(id = chapter.id, memo = buildJsonObject {}))
            assertNull(ConnectionChapterMetadata.pagesCount(checkNotNull(chapters.getChapterById(chapter.id)).memo))
            repeat(2) {
                FixtureActivityLauncher.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, manga.id, chapter.id, profile.id),
                ).use { scenario ->
                    withTimeout(20_000) {
                        while (true) {
                            var ready = false
                            scenario.onActivity { activity ->
                                val state = activity.viewModel.state.value
                                ready = state.totalPages == 3 && state.archiveLoadingStage == null &&
                                    descendants(activity.window.decorView)
                                        .filterIsInstance<SubsamplingScaleImageView>().any { image -> image.isReady }
                            }
                            if (ready) break
                            delay(50)
                        }
                    }
                    val saved = checkNotNull(chapters.getChapterById(chapter.id))
                    assertEquals(3, ConnectionChapterMetadata.pagesCount(saved.memo))
                }
            }
        } finally {
            try {
                profiles.remove(profile.id).getOrThrow()
            } finally {
                connections.activeConnectionId.set(previousActive)
                restores.asReversed().forEach { it() }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }
}
