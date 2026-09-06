package koharia.source.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.LibraryConnectionProfile
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
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
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.util.Date

@RunWith(AndroidJUnit4::class)
class LocalLibraryItemActionsDeviceTest {
    @Test
    fun unopenedBookCanBeMarkedReadResetAndMovedWithoutChangingItsFile() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "local-actions-test-").toFile()
        val sourceId = 9_200_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val progress: EpubProgressRepository = Injekt.get()
        try {
            val file = directory.resolve("book.txt").apply { writeText("A local book for read status testing.") }
            val originalBytes = file.readBytes()
            preferences.setConfig(
                LocalLibraryConfig(
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = "test-root",
                            treeUri = directory.toURI().toString(),
                            contentType = LocalLibraryContentType.BOOKS,
                            bookshelfId = "first",
                        ),
                    ),
                    bookshelves = listOf("first", "second").map {
                        LocalBookshelf(
                            it,
                            it,
                            LocalLibraryContentType.BOOKS,
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                        )
                    },
                ),
            )
            val source = LocalFolderSource(
                context,
                sourceId,
                "Actions test",
                LibraryConnectionProfile(sourceId, "local-folder", "Actions test"),
            )
            val actions = LocalLibraryItemActions(Injekt.get(), chapters, Injekt.get(), progress)
            source.refreshLibrary().getOrThrow()
            val manga = mangas.getMangaBySourceId(sourceId).single()
            assertTrue(chapters.getChapterByMangaId(manga.id).isEmpty())
            actions.markRead(source, manga, read = true)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            assertTrue(chapter.read)
            chapters.update(ChapterUpdate(id = chapter.id, read = false, lastPageRead = 12))
            progress.upsertProgress(
                EpubProgress(chapter.id, manga.id, manga.url, "{}", 0.65, 12, Date(), null),
            )
            actions.markRead(source, manga, read = false)
            val unread = chapters.getChapterById(chapter.id)!!
            assertFalse(unread.read)
            assertEquals(0L, unread.lastPageRead)
            assertNull(progress.getProgress(chapter.id))
            // EPUB locators may exist even when the comic page counter is still zero.
            progress.upsertProgress(EpubProgress(chapter.id, manga.id, manga.url, "{}", 0.4, 8, Date(), null))
            actions.markRead(source, manga, read = false)
            assertNull(progress.getProgress(chapter.id))
            source.moveMangaToLibraryShelf(manga.url, "second").getOrThrow()
            assertEquals("second", source.currentLibraryShelfId(manga.url))
            assertTrue(source.browseIndexedLibrary(query = "", bookshelfId = "first").isEmpty())
            assertEquals(
                listOf(manga.id),
                source.browseIndexedLibrary(query = "", bookshelfId = "second").map {
                    it.id
                },
            )
            source.refreshLibrary().getOrThrow()
            assertEquals("second", source.currentLibraryShelfId(manga.url))
            assertTrue(originalBytes.contentEquals(file.readBytes()))
        } finally {
            mangas.deleteMangaBySourceId(sourceId)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
