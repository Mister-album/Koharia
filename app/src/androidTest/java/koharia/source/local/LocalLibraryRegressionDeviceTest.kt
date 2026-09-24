package koharia.source.local

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedAppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalLibraryRegressionDeviceTest {
    @Test
    fun comicPagesAndReadStateSurviveRefreshAndNewSeriesInheritDefaults() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "local-regression-").toFile()
        val sourceId = 9_700_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val sync: SyncChaptersWithSource = Injekt.get()
        val defaults = Injekt.get<SharedAppPreferences>().libraryPreferences().chapterCoverDisplayMode
        val wasSet = defaults.isSet()
        val previousDefault = defaults.get()
        try {
            defaults.set(Manga.CHAPTER_COVER_DISPLAY_COMFORTABLE)
            val book = directory.resolve("series/book.cbz").apply { parentFile!!.mkdirs() }
            writeComic(book, 3)
            val base = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            preferences.setConfig(
                base.copy(
                    setupCompleted = true,
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = "root",
                            treeUri = directory.toURI().toString(),
                            contentType = LocalLibraryContentType.COMICS,
                            bookshelfId = base.defaultBookshelfId(LocalLibraryContentType.COMICS),
                        ),
                    ),
                ),
            )
            val source = LocalFolderSource(
                context,
                sourceId,
                "Regression",
                LibraryConnectionProfile(sourceId, LocalFolderConnectionProvider.ID, "Regression"),
            )
            source.refreshLibrary().getOrThrow()
            val manga = mangas.getMangaBySourceId(sourceId).single()
            assertFalse(manga.favorite)
            assertEquals(Manga.CHAPTER_COVER_DISPLAY_TEXT, manga.chapterCoverDisplayMode)
            sync.await(source.getChapterList(manga.toSManga()), manga, source, manualFetch = false)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            val loader = LocalPageLoader(ReaderChapter(chapter), source, source)
            try {
                assertEquals(3, loader.getPages().size)
                assertEquals(3, loader.progressPageCount)
                chapters.update(
                    ChapterUpdate(
                        id = chapter.id,
                        read = true,
                        lastPageRead = 2,
                        memo = ConnectionChapterMetadata.withPagesCount(chapter.memo, loader.progressPageCount!!),
                    ),
                )
            } finally {
                loader.recycle()
            }
            defaults.set(Manga.CHAPTER_COVER_DISPLAY_TEXT)
            source.refreshLibrary().getOrThrow()
            val refreshed = source.getChapterList(manga.toSManga())
            assertEquals(3, ConnectionChapterMetadata.pagesCount(refreshed.single().memo))
            sync.await(refreshed, manga, source, manualFetch = false)
            assertTrue(chapters.getChapterByMangaId(manga.id).single().read)
            assertEquals(Manga.CHAPTER_COVER_DISPLAY_TEXT, mangas.getMangaById(manga.id).chapterCoverDisplayMode)
            assertEquals(
                Manga.CHAPTER_COVER_DISPLAY_TEXT,
                mangas.getMangaById(manga.id).withChapterCoverDisplayMode(defaults.get()).chapterCoverDisplayMode,
            )
            defaults.set(Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE)
            assertEquals(
                Manga.CHAPTER_COVER_DISPLAY_COVER_AND_TITLE,
                mangas.getMangaById(manga.id).withChapterCoverDisplayMode(defaults.get()).chapterCoverDisplayMode,
            )
            writeComic(book, 4)
            source.refreshLibrary().getOrThrow()
            assertNull(ConnectionChapterMetadata.pagesCount(source.getChapterList(manga.toSManga()).single().memo))
            // A dangling ancillary file must not prevent healthy books from being indexed.
            Files.createSymbolicLink(
                directory.resolve("series/unreadable.xml").toPath(),
                directory.resolve("missing").toPath(),
            )
            val config = preferences.getConfig()
            preferences.setConfig(
                config.copy(
                    bookshelves = config.bookshelves + LocalBookshelf(
                        id = "individual-regression",
                        name = "Individual regression",
                        contentType = LocalLibraryContentType.COMICS,
                        organizationMode = LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                    ),
                    roots = config.roots.map { it.copy(bookshelfId = "individual-regression") },
                ),
            )
            source.refreshLibrary().getOrThrow()
            assertTrue(
                preferences.getIndex().items.any {
                    it.kind == LocalLibraryItem.Kind.FILE_ENTRY && it.relativePath == "series/book.cbz"
                },
            )
            val secondPreferences = LocalLibraryPreferences(sourceId, Json)
            assertEquals(preferences.getIndex(), secondPreferences.getIndex())
            preferences.setIndex(LocalLibraryIndex(scannedAt = 1))
            assertTrue(secondPreferences.getIndex().items.isEmpty())
        } finally {
            if (wasSet) defaults.set(previousDefault) else defaults.delete()
            mangas.deleteMangaBySourceId(sourceId)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun writeComic(file: File, pages: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val image = try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { page ->
                zip.putNextEntry(ZipEntry("$page.png"))
                zip.write(image)
                zip.closeEntry()
            }
        }
    }
}
