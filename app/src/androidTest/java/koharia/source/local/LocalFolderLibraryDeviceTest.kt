package koharia.source.local

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalFolderLibraryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun fixture(block: suspend (File, LocalFolderSource, LocalLibraryPreferences) -> Unit) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "folder-library-").toFile().canonicalFile
        val id = 9_500_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(id, Json)
        val repository: MangaRepository = Injekt.get()
        try {
            preferences.setConfig(
                LocalLibraryConfig(
                    setupCompleted = true,
                    metadataStorage = LocalMetadataStorage.FOLDER_DIRECTORY,
                    bookshelves = listOf(
                        LocalBookshelf(
                            "folders",
                            "Folders",
                            LocalLibraryContentType.COMICS,
                            LocalLibraryOrganizationMode.FOLDER,
                        ),
                    ),
                    roots = listOf(
                        LocalLibraryRootConfig(
                            "root",
                            root.toURI().toString(),
                            contentType = LocalLibraryContentType.COMICS,
                            bookshelfId = "folders",
                        ),
                    ),
                ),
            )
            val source =
                LocalFolderSource(
                    context,
                    id,
                    "Folders",
                    LibraryConnectionProfile(id, LocalFolderConnectionProvider.ID, "Folders"),
                )
            block(root, source, preferences)
        } finally {
            repository.deleteMangaBySourceId(id)
            context.getSharedPreferences("source_$id", 0).edit().clear().commit()
            check(root.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun comic(root: File, path: String) {
        val file = root.resolve(path).apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("001.png"))
            it.write(image())
            it.closeEntry()
        }
    }

    @Test
    fun nestedBrowsingMovesAndExternalDirectoryRenamePreserveProgress() = runBlocking(Dispatchers.IO) {
        fixture { root, source, preferences ->
            comic(root, "A/B/Book.cbz")
            comic(root, "Root.cbz")
            source.refreshLibrary().getOrThrow()
            assertEquals(setOf("A", "Root"), source.browseIndexedLibrary("").map { it.title }.toSet())
            val all: MangaRepository = Injekt.get()
            val folder = all.getMangaBySourceId(source.id).first { it.title == "B" }
            val book = source.browseIndexedLibrary(
                all.getMangaBySourceId(source.id),
                "",
                parentUrl = folder.url,
            ).single()
            assertTrue(source.filterLibraryEntries(listOf(book)).any { it.id == book.id })
            val sync: SyncChaptersWithSource = Injekt.get()
            val chapters: ChapterRepository = Injekt.get()
            sync.await(source.getChapterList(book.toSManga()), book, source, manualFetch = false)
            val chapter = chapters.getChapterByMangaId(book.id).single()
            chapters.update(ChapterUpdate(id = chapter.id, read = true, lastPageRead = 1))
            source.updateMetadata(book.url, LibraryMetadata(title = "Edited title")).getOrThrow()
            val sidecars = root.resolve("A/B/.koharia/metadata")
            assertEquals("A/B/Book.cbz", source.indexedEntry(book.url)?.relativePath)
            assertTrue(
                "metadata sidecar missing",
                sidecars.resolve("Book.cbz.ComicInfo.xml").exists(),
            )
            val conflict = sidecars.resolve("Renamed.cbz.ComicInfo.xml").apply { writeText("occupied") }
            assertTrue(
                "metadata conflict was not rejected",
                source.relocateEntry(book.url, newName = "Renamed.cbz").isFailure,
            )
            assertTrue(root.resolve("A/B/Book.cbz").exists())
            assertFalse(source.hasPendingFolderOperation())
            assertTrue(conflict.delete())
            source.relocateEntry(book.url, newName = "Renamed.cbz").getOrThrow()
            assertTrue(root.resolve("A/B/Renamed.cbz").exists())
            assertTrue(sidecars.resolve("Renamed.cbz.ComicInfo.xml").exists())
            assertEquals(chapter.url, source.getChapterList(book.toSManga()).single().url)
            assertTrue(root.resolve("A").renameTo(root.resolve("Z")))
            source.refreshLibrary().getOrThrow()
            assertEquals("Z/B/Renamed.cbz", source.indexedEntry(book.url)?.relativePath)
            assertEquals(chapter.id, chapters.getChapterByMangaId(book.id).single().id)
            assertTrue(chapters.getChapterByMangaId(book.id).single().read)
            source.relocateEntry(book.url, destination = "").getOrThrow()
            assertTrue(root.resolve("Renamed.cbz").exists())
            assertTrue(root.resolve(".koharia/metadata/Renamed.cbz.ComicInfo.xml").exists())
            assertEquals("Edited title", source.getMangaDetails(book.toSManga()).title)
            assertEquals("Renamed.cbz", source.indexedEntry(book.url)?.relativePath)
            assertFalse(source.hasPendingFolderOperation())
            assertNotNull(preferences.getIndex().itemsByKey[source.indexedEntry(book.url)!!.itemKey])
        }
    }

    @Test
    fun imageComicExcludesCoverAndFallsBackToFolderWhenContentsChange() = runBlocking(Dispatchers.IO) {
        fixture { root, source, _ ->
            val folder = root.resolve("Images").apply { mkdirs() }
            listOf("001.png", "002.png", "cover.png").forEach { folder.resolve(it).writeBytes(image()) }
            source.refreshLibrary().getOrThrow()
            val manga = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, source.indexedEntry(manga.url)?.kind)
            assertTrue(source.canReadAsImageComic(manga.url))
            assertNotNull(source.loadChapterThumbnail(manga.url))
            assertTrue(source.isIndividualFileEntry(manga.url))
            val sync: SyncChaptersWithSource = Injekt.get()
            val chapters: ChapterRepository = Injekt.get()
            sync.await(source.getChapterList(manga.toSManga()), manga, source, manualFetch = false)
            val loader = LocalPageLoader(ReaderChapter(chapters.getChapterByMangaId(manga.id).single()), source, source)
            try {
                assertEquals(2, loader.getPages().size)
            } finally {
                loader.recycle()
            }
            folder.resolve("Notes.txt").writeText("Not an image")
            source.refreshLibrary().getOrThrow()
            val changed = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FOLDER, source.indexedEntry(changed.url)?.kind)
            assertFalse(source.indexedEntry(changed.url)!!.imageComic)
            assertTrue(source.getChapterList(changed.toSManga()).isEmpty())
        }
    }

    @Test
    fun imageComicRecognizesImagesWithoutFileExtensions() = runBlocking(Dispatchers.IO) {
        fixture { root, source, _ ->
            val folder = root.resolve("Extensionless").apply { mkdirs() }
            folder.resolve("001").writeBytes(image())
            folder.resolve("002").writeBytes(image())
            source.refreshLibrary().getOrThrow()

            val manga = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, source.indexedEntry(manga.url)?.kind)
            assertTrue(source.canReadAsImageComic(manga.url))
            assertTrue(source.isIndividualFileEntry(manga.url))
        }
    }

    @Test
    fun imageComicIgnoresOperatingSystemThumbnailFiles() = runBlocking(Dispatchers.IO) {
        fixture { root, source, _ ->
            val folder = root.resolve("WithThumbnails").apply { mkdirs() }
            folder.resolve("001.png").writeBytes(image())
            folder.resolve("002.png").writeBytes(image())
            folder.resolve("Thumbs.db").writeBytes(byteArrayOf(1, 2, 3))
            folder.resolve("desktop.ini").writeText("[ViewState]")
            source.refreshLibrary().getOrThrow()

            val manga = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, source.indexedEntry(manga.url)?.kind)
            assertTrue(source.canReadAsImageComic(manga.url))
            assertTrue(source.isIndividualFileEntry(manga.url))
        }
    }

    @Test
    fun mixedFolderRemainsAContainerAndKeepsSubdirectoriesSeparate() = runBlocking(Dispatchers.IO) {
        fixture { root, source, _ ->
            val folder = root.resolve("NestedImages").apply { mkdirs() }
            folder.resolve("001.png").writeBytes(image())
            folder.resolve("003.png").writeBytes(image())
            folder.resolve("More/002.png").apply {
                parentFile!!.mkdirs()
                writeBytes(image())
            }
            folder.resolve("More/Thumbs.db").writeBytes(byteArrayOf(1, 2, 3))
            folder.resolve("Empty").mkdirs()
            folder.resolve("More/Notes.txt").writeText("Child contents do not affect parent matching")

            source.refreshLibrary().getOrThrow()
            val manga = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FOLDER, source.indexedEntry(manga.url)?.kind)
            assertTrue(source.canMergeImageSeries(manga.url))
            assertFalse(source.canReadAsImageComic(manga.url))
            assertFalse(source.indexedEntry(manga.url)!!.imageComic)
            assertTrue(source.getChapterList(manga.toSManga()).isEmpty())

            val entries = source.browseIndexedLibrary(
                Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                "",
                parentUrl = manga.url,
            )
            assertTrue(entries.any { it.title == "NestedImages" && source.canReadAsImageComic(it.url) })
            assertEquals(
                setOf("NestedImages", "More", "Empty"),
                entries.map { it.title }.toSet(),
            )
            source.setImageComic(manga.url, false).getOrThrow()
            assertEquals(
                setOf("001", "003", "More", "Empty"),
                source.browseIndexedLibrary(
                    Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                    "",
                    parentUrl = manga.url,
                ).map { it.title }.toSet(),
            )
            source.setImageComic(manga.url, true).getOrThrow()
            assertTrue(
                source.browseIndexedLibrary(
                    Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                    "",
                    parentUrl = manga.url,
                ).any { it.title == "NestedImages" },
            )
        }
    }

    @Test
    fun mixedFolderContainerAndImageSeriesKeepMetadataAndShelfRolesSeparate() = runBlocking(Dispatchers.IO) {
        fixture { root, source, preferences ->
            val folder = root.resolve("Mixed").apply { mkdirs() }
            folder.resolve("001.png").writeBytes(image())
            folder.resolve("002.png").writeBytes(image())
            folder.resolve("Child").mkdirs()
            folder.resolve("ComicInfo.xml").writeText(
                "<ComicInfo><Series>Container metadata must be ignored</Series></ComicInfo>",
            )

            source.refreshLibrary().getOrThrow()
            val repository: MangaRepository = Injekt.get()
            val container = source.browseIndexedLibrary("").single()
            assertTrue(source.isMetadataEditable(container.url))
            assertEquals(
                setOf(
                    LibraryMetadataField.TITLE,
                    LibraryMetadataField.AUTHOR,
                    LibraryMetadataField.DESCRIPTION,
                    LibraryMetadataField.GENRES,
                ),
                source.editableMetadataFields(container.url),
            )
            assertFalse(source.isLibraryShelfAssignable(container.url))
            assertTrue(source.compatibleLibraryShelves(container.url).isEmpty())
            assertEquals(null, source.currentLibraryShelfId(container.url))
            assertEquals("Mixed", source.getMangaDetails(container.toSManga()).title)
            val scannedAt = preferences.getIndex().scannedAt
            source.updateMetadata(
                container.url,
                LibraryMetadata(
                    title = "Edited folder",
                    author = "Folder author",
                    description = "Folder summary",
                    genres = listOf("collection", "local"),
                    editedFields = setOf(
                        LibraryMetadataField.TITLE,
                        LibraryMetadataField.AUTHOR,
                        LibraryMetadataField.DESCRIPTION,
                        LibraryMetadataField.GENRES,
                    ),
                ),
            ).getOrThrow()
            assertEquals(scannedAt, preferences.getIndex().scannedAt)
            val folderMetadata = checkNotNull(source.readMetadata(container.url))
            assertEquals("Edited folder", folderMetadata.title)
            assertEquals("Folder author", folderMetadata.author)
            assertEquals("Folder summary", folderMetadata.description)
            assertEquals(listOf("collection", "local"), folderMetadata.genres)
            val updatedContainer = source.browseIndexedLibrary("").single()
            assertEquals("Edited folder", updatedContainer.title)
            assertEquals("Folder author", updatedContainer.author)
            assertEquals("Folder summary", updatedContainer.description)
            assertEquals(listOf("collection", "local"), updatedContainer.genre)
            assertEquals(
                listOf(updatedContainer.url),
                source.browseIndexedLibrary("", filters = LocalLibraryFilters(genre = "collection"))
                    .map { it.url },
            )
            source.updateMetadata(
                container.url,
                LibraryMetadata(
                    author = "",
                    description = "",
                    editedFields = setOf(
                        LibraryMetadataField.AUTHOR,
                        LibraryMetadataField.DESCRIPTION,
                        LibraryMetadataField.GENRES,
                    ),
                ),
            ).getOrThrow()
            val clearedMetadata = checkNotNull(source.readMetadata(container.url))
            assertTrue(clearedMetadata.author.isNullOrBlank())
            assertTrue(clearedMetadata.description.isNullOrBlank())
            assertTrue(clearedMetadata.genres.isEmpty())

            val imageSeries = source.browseIndexedLibrary(
                repository.getMangaBySourceId(source.id),
                "",
                parentUrl = container.url,
            ).first { source.indexedEntry(it.url)?.kind == LocalLibraryItem.Kind.FILE_ENTRY }
            assertTrue(source.isMetadataEditable(imageSeries.url))
            assertTrue(source.isLibraryShelfAssignable(imageSeries.url))
            source.updateMetadata(
                imageSeries.url,
                LibraryMetadata(title = "Edited images", status = SManga.COMPLETED),
            ).getOrThrow()

            val sidecar = folder.resolve(".koharia/metadata/.koharia-image-series.ComicInfo.xml")
            assertTrue(sidecar.isFile)
            val indexed = checkNotNull(source.indexedEntry(imageSeries.url))
            val parsed = LocalMetadataStore(
                context = context,
                profileId = source.id,
                json = Json,
                xml = Injekt.get<XML>(),
                preferences = preferences,
            ).readAndMigrate(
                key = indexed.itemKey,
                itemDirectory = checkNotNull(UniFile.fromUri(context, Uri.fromFile(folder))),
                type = LocalLibraryContentType.COMICS,
                role = LocalMetadataRole.FOLDER_IMAGE_SERIES,
            )
            assertEquals("Edited images", parsed?.title)
            assertEquals(SManga.COMPLETED, parsed?.status)
        }
    }

    @Test
    fun folderContainerMetadataSuggestionsRecognizeFolderSeriesAndAuthor() = runBlocking(Dispatchers.IO) {
        fixture { root, source, _ ->
            val folderName = "[Recognized Series][Recognized Author]"
            comic(root, "$folderName/01.cbz")
            source.refreshLibrary().getOrThrow()

            val container = source.browseIndexedLibrary("").single()
            val suggestion = source.generateMetadataSuggestion(
                container.url,
                MetadataFilenameTemplate.AUTO,
            ).getOrThrow()

            assertEquals("Recognized Series", suggestion.metadata.title)
            assertEquals("Recognized Author", suggestion.metadata.author)
            assertEquals(MetadataSuggestionSource.FOLDER, suggestion.fieldSources[LibraryMetadataField.TITLE])
            assertEquals(MetadataSuggestionSource.FOLDER, suggestion.fieldSources[LibraryMetadataField.AUTHOR])
        }
    }

    @Test
    fun disabledImageSeriesKeepsFolderVisibleAfterMovingLastChild() = runBlocking(Dispatchers.IO) {
        for (storage in listOf(LocalMetadataStorage.DATABASE, LocalMetadataStorage.FOLDER_DIRECTORY)) {
            fixture { root, source, preferences ->
                preferences.setConfig(preferences.getConfig().copy(metadataStorage = storage))
                val folder = root.resolve("Images").apply { mkdirs() }
                folder.resolve("001.png").writeBytes(image())
                folder.resolve("002.png").writeBytes(image())
                folder.resolve("Child").mkdirs()
                source.refreshLibrary().getOrThrow()
                val container = source.browseIndexedLibrary("").single()
                source.setImageComic(container.url, false).getOrThrow()
                val repository: MangaRepository = Injekt.get()
                val child = source.browseIndexedLibrary(
                    repository.getMangaBySourceId(source.id),
                    "",
                    parentUrl = container.url,
                ).first { it.title == "Child" }
                source.relocateEntry(child.url, destination = "").getOrThrow()
                source.refreshLibrary().getOrThrow()

                val remaining = source.browseIndexedLibrary("").first { it.title == "Images" }
                assertEquals(container.url, remaining.url)
                assertEquals(LocalLibraryItem.Kind.FOLDER, source.indexedEntry(remaining.url)?.kind)
                assertEquals(false, source.indexedEntry(remaining.url)?.imageComicOverride)
                assertTrue(source.getChapterList(remaining.toSManga()).isEmpty())
                assertEquals(
                    setOf("001", "002"),
                    source.browseIndexedLibrary(
                        repository.getMangaBySourceId(source.id),
                        "",
                        parentUrl = remaining.url,
                    ).map { it.title }.toSet(),
                )
                source.setImageComic(remaining.url, true).getOrThrow()
                assertTrue(
                    source.browseIndexedLibrary("").any {
                        it.title == "Images" &&
                            source.canReadAsImageComic(it.url)
                    },
                )
            }
        }
    }

    @Test
    fun imageComicDoesNotCountImagesInSubdirectories() = runBlocking(Dispatchers.IO) {
        fixture { root, source, preferences ->
            val folder = root.resolve("Parent").apply { mkdirs() }
            val child = folder.resolve("Child").apply { mkdirs() }
            child.resolve("001.png").writeBytes(image())
            child.resolve("002.png").writeBytes(image())
            source.refreshLibrary().getOrThrow()
            val manga = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FOLDER, source.indexedEntry(manga.url)?.kind)
            assertFalse(source.canReadAsImageComic(manga.url))
            assertEquals(
                LocalLibraryItem.Kind.FOLDER,
                preferences.getIndex().items.single { it.relativePath == "Parent" }.kind,
            )
            val childComic = source.browseIndexedLibrary(
                Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                "",
                parentUrl = manga.url,
            ).single()
            assertEquals("Child", childComic.title)
            assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, source.indexedEntry(childComic.url)?.kind)
            folder.resolve("001.png").writeBytes(image())
            folder.resolve("002.png").writeBytes(image())
            source.refreshLibrary().getOrThrow()
            val updatedParent = source.browseIndexedLibrary("").single()
            assertEquals(LocalLibraryItem.Kind.FOLDER, source.indexedEntry(updatedParent.url)?.kind)
            assertFalse(source.canReadAsImageComic(updatedParent.url))
            val updatedChildren = source.browseIndexedLibrary(
                Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                "",
                parentUrl = updatedParent.url,
            )
            assertEquals(setOf("Parent", "Child"), updatedChildren.map { it.title }.toSet())
            assertTrue(updatedChildren.all { source.canReadAsImageComic(it.url) })
        }
    }

    @Test
    fun externalMissingRetainsRecordAndDeletionRejectsNewChildren() = runBlocking(Dispatchers.IO) {
        fixture { root, source, preferences ->
            comic(root, "A/Book.cbz")
            source.refreshLibrary().getOrThrow()
            val folder = source.browseIndexedLibrary("").single()
            val plan = source.prepareFileDeletion(listOf(folder))
            comic(root, "A/New.cbz")
            assertEquals(1, source.deleteLocalFiles(plan).failed.size)
            assertTrue(root.resolve("A/Book.cbz").exists())
            val book = preferences.getIndex().items.first { it.relativePath == "A/Book.cbz" }
            assertTrue(root.resolve("A/Book.cbz").delete())
            source.refreshLibrary().getOrThrow()
            assertTrue(preferences.getIndex().itemsByKey.getValue(book.itemKey).missing)
        }
    }

    @Test
    fun folderImportTargetsCurrentDirectoryAndSupportsImagesWithoutCreatingSeries() = runBlocking(Dispatchers.IO) {
        fixture { root, source, preferences ->
            root.resolve("Parent/Images").mkdirs()
            root.resolve("Sibling").mkdirs()
            source.refreshLibrary().getOrThrow()
            val folder = preferences.getIndex().items.single { it.relativePath == "Parent/Images" }
            val url = source.entryUrl(folder)
            val destination = checkNotNull(source.folderImportDestination(url))
            assertTrue("png" in destination.supportedExtensions)
            val incoming = root.resolve(".incoming.png").apply { writeBytes(image()) }
            val request = koharia.connection.ConnectionMediaImportRequest(
                destinationId = destination.id,
                shelfId = destination.defaultShelfId,
                seriesName = "Must not create series",
                items = listOf(
                    koharia.connection.ConnectionMediaImportItem(
                        uri = incoming.toURI().toString(),
                        displayName = "001.png",
                        mimeType = "image/png",
                        sizeBytes = incoming.length(),
                        extension = "png",
                    ),
                ),
                targetFolderUrl = url,
            )
            val imported = source.importMedia(request).getOrThrow()
            assertTrue(root.resolve("Parent/Images/001.png").isFile)
            assertFalse(root.resolve("001.png").exists())
            assertFalse(root.resolve("Must not create series").exists())
            assertEquals("Parent/Images/001.png", source.indexedEntry(imported.resourceUrls.single())?.relativePath)
            assertFalse(source.canReadAsImageComic(url))

            source.relocateEntry(url, newName = "Renamed").getOrThrow()
            source.importMedia(request).getOrThrow()
            assertEquals(2, root.resolve("Parent/Renamed").listFiles()!!.count { it.extension == "png" })
            assertFalse(root.resolve("Parent/Images").exists())
            val parent = source.browseIndexedLibrary("").first { it.title == "Parent" }
            val renamed = source.browseIndexedLibrary(
                Injekt.get<MangaRepository>().getMangaBySourceId(source.id),
                "",
                parentUrl = parent.url,
            ).single()
            assertTrue(source.canReadAsImageComic(renamed.url))
            assertTrue(source.isIndividualFileEntry(renamed.url))
            assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, source.indexedEntry(renamed.url)?.kind)

            val topLevel = source.importMedia(request.copy(targetFolderUrl = null)).getOrThrow()
            assertTrue(root.resolve("001.png").isFile)
            assertEquals("001.png", source.indexedEntry(topLevel.resourceUrls.single())?.relativePath)
            root.resolve("Parent/Renamed").renameTo(root.resolve("Parent/Missing"))
            assertTrue(source.importMedia(request).isFailure)
            assertEquals(1, root.listFiles()!!.count { it.name == "001.png" })
        }
    }
}
