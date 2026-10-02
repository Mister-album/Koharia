package koharia.source.local

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryMetadata
import koharia.connection.LibraryMetadataField
import koharia.connection.MetadataFilenameTemplate
import koharia.connection.MetadataSuggestionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalMetadataIsolationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository: MangaRepository get() = Injekt.get()

    private suspend fun fixture(
        storage: LocalMetadataStorage,
        mode: LocalLibraryOrganizationMode = LocalLibraryOrganizationMode.FOLDER,
        type: LocalLibraryContentType = LocalLibraryContentType.COMICS,
        block: suspend (File, LocalFolderSource, LocalLibraryPreferences) -> Unit,
    ) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "metadata-isolation-").toFile().canonicalFile
        val id = 9_700_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(id, Json)
        try {
            val rootConfig = LocalLibraryRootConfig(
                id = "root",
                treeUri = root.toURI().toString(),
                contentType = type,
                bookshelfId = "shelf",
            )
            preferences.setConfig(
                LocalLibraryConfig(
                    setupCompleted = true,
                    metadataStorage = storage,
                    bookshelves = listOf(LocalBookshelf("shelf", "Test shelf", type, mode)),
                    roots = listOf(rootConfig),
                ),
            )
            val profile = LibraryConnectionProfile(id, LocalFolderConnectionProvider.ID, "Metadata tests")
            val source = LocalFolderSource(context, id, "Metadata tests", profile)
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

    private fun comicInfo(title: String, author: String) = """
        <ComicInfo><Series>$title</Series><Writer>$author</Writer></ComicInfo>
    """.trimIndent()

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun epub(file: File) = zip(
        file,
        mapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent().toByteArray(),
            "content.opf" to """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">test</dc:identifier>
                    <dc:title>Embedded book</dc:title><dc:creator>Chapter author</dc:creator>
                    <dc:description>Chapter summary</dc:description><dc:subject>Chapter tag</dc:subject>
                    <meta name="calibre:series" content="Embedded collection"/>
                </metadata>
                <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="page"/></spine>
            </package>
            """.trimIndent().toByteArray(),
            "page.xhtml" to """
            <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Test</title></head><body><p>Test</p></body>
            </html>
            """.trimIndent().toByteArray(),
        ),
    )

    @Test
    fun singleFileNeverAdoptsParentMetadataAndSuggestionsRespectLocks() = runBlocking(Dispatchers.IO) {
        for (storage in LocalMetadataStorage.entries) {
            for (mode in listOf(LocalLibraryOrganizationMode.FOLDER, LocalLibraryOrganizationMode.INDIVIDUAL_FILES)) {
                fixture(storage, mode) { root, source, _ ->
                    zip(
                        root.resolve("Parent/Only.cbz"),
                        mapOf(
                            "001.png" to image(),
                            "ComicInfo.xml" to
                                """
                            <ComicInfo><Title>Own comic</Title><Series>Own comic</Series>
                                <Writer>Own author</Writer><Summary>Own summary</Summary>
                            </ComicInfo>
                                """.trimIndent().toByteArray(),
                        ),
                    )
                    root.resolve("Parent/ComicInfo.xml").writeText(comicInfo("Wrong parent", "Wrong author"))
                    source.refreshLibrary().getOrThrow()
                    val comic = repository.getMangaBySourceId(source.id)
                        .single { source.indexedEntry(it.url)?.format == "cbz" }
                    assertEquals("Own comic", comic.title)
                    assertEquals("Own author", source.getMangaDetails(comic.toSManga()).author)
                    val embedded = source.generateMetadataSuggestion(comic.url, MetadataFilenameTemplate.AUTO)
                        .getOrThrow()
                    assertEquals(
                        MetadataSuggestionSource.COMICINFO_EMBEDDED,
                        embedded.fieldSources[LibraryMetadataField.AUTHOR],
                    )
                    root.resolve(
                        "Parent/Only.ComicInfo.xml",
                    ).writeText(comicInfo("Dedicated comic", "Dedicated author"))
                    source.refreshLibrary().getOrThrow()
                    assertEquals("Dedicated comic", source.getMangaDetails(comic.toSManga()).title)
                    val edited = LibraryMetadata(title = "Locked title", lockedFields = setOf("title"))
                    source.readMetadata(comic.url)
                    source.updateMetadata(comic.url, edited).getOrThrow()
                    val suggestion = source.generateMetadataSuggestion(comic.url, MetadataFilenameTemplate.AUTO)
                        .getOrThrow()
                    assertFalse(LibraryMetadataField.TITLE in suggestion.fieldSources)
                    assertEquals("Dedicated author", suggestion.metadata.author)
                    assertEquals(MetadataSuggestionSource.SIDECAR, suggestion.fieldSources[LibraryMetadataField.AUTHOR])
                }
            }
            fixture(
                storage = storage,
                mode = LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                type = LocalLibraryContentType.BOOKS,
            ) { root, source, _ ->
                epub(root.resolve("Parent/Only.epub"))
                root.resolve("Parent/metadata.opf").writeText(
                    """
                    <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Wrong parent</dc:title><dc:creator>Wrong author</dc:creator>
                    </metadata></package>
                    """.trimIndent(),
                )
                source.refreshLibrary().getOrThrow()
                val book = source.browseIndexedLibrary("").single()
                assertEquals("Embedded book", book.title)
                assertEquals("Chapter author", book.author)
                val suggestion = source.generateMetadataSuggestion(book.url, MetadataFilenameTemplate.AUTO).getOrThrow()
                assertEquals("Embedded book", suggestion.metadata.title)
            }
        }
    }

    @Test
    fun seriesNeverAutomaticallyAdoptsChapterMetadataAndRenameRetainsEditsAndProgress() = runBlocking(Dispatchers.IO) {
        for (storage in LocalMetadataStorage.entries) {
            fixture(
                storage = storage,
                mode = LocalLibraryOrganizationMode.SERIES,
                type = LocalLibraryContentType.BOOKS,
            ) { root, source, preferences ->
                epub(root.resolve("Collection/01.epub"))
                source.refreshLibrary().getOrThrow()
                val series = source.browseIndexedLibrary("").single()
                assertEquals("Collection", series.title)
                assertNull(series.author)
                assertNull(series.description)
                assertTrue(series.genre.isNullOrEmpty())
                repository.update(
                    MangaUpdate(id = series.id, author = "Stale chapter author", description = "Stale summary"),
                )
                preferences.setIndex(preferences.getIndex().copy(schemaVersion = 6))
                source.refreshLibrary().getOrThrow()
                assertNull(repository.getMangaById(series.id).author)
                assertNull(repository.getMangaById(series.id).description)
                val suggestion = source.generateMetadataSuggestion(series.url, MetadataFilenameTemplate.AUTO)
                    .getOrThrow()
                assertEquals("Chapter author", suggestion.metadata.author)
                assertEquals(
                    MetadataSuggestionSource.CHAPTER_EMBEDDED,
                    suggestion.fieldSources[LibraryMetadataField.AUTHOR],
                )
                val edited = LibraryMetadata(title = "Edited series", author = "Series author")
                source.updateMetadata(series.url, edited).getOrThrow()
                val sync: SyncChaptersWithSource = Injekt.get()
                val chapters: ChapterRepository = Injekt.get()
                sync.await(source.getChapterList(series.toSManga()), series, source, manualFetch = false)
                val chapter = chapters.getChapterByMangaId(series.id).single()
                chapters.update(ChapterUpdate(id = chapter.id, read = true, lastPageRead = 1))
                assertTrue(root.resolve("Collection").renameTo(root.resolve("Renamed")))
                source.refreshLibrary().getOrThrow()
                val renamed = source.browseIndexedLibrary("").single()
                assertEquals(series.id, renamed.id)
                assertEquals("Edited series", renamed.title)
                assertEquals("Series author", renamed.author)
                assertEquals(series.url, source.mediaImportSeries("root").single().id)
                assertEquals("Edited series", source.mediaImportSeries("root").single().name)
                assertEquals(chapter.url, source.getChapterList(renamed.toSManga()).single().url)
                assertTrue(chapters.getChapterByMangaId(renamed.id).single().read)
                assertNotNull(source.localChapterFile(chapter.url))
                val config = preferences.getConfig()
                preferences.setConfig(
                    config.copy(
                        bookshelves = config.bookshelves + LocalBookshelf(
                            "alternate",
                            "Alternate shelf",
                            LocalLibraryContentType.BOOKS,
                            LocalLibraryOrganizationMode.SERIES,
                        ),
                    ),
                )
                val incoming = root.resolve(".incoming.epub")
                epub(incoming)
                val request = koharia.connection.ConnectionMediaImportRequest(
                    destinationId = "root",
                    shelfId = "alternate",
                    seriesName = "New import",
                    items = listOf(
                        koharia.connection.ConnectionMediaImportItem(
                            uri = incoming.toURI().toString(),
                            displayName = "02.epub",
                            mimeType = "application/epub+zip",
                            sizeBytes = incoming.length(),
                            extension = "epub",
                        ),
                    ),
                )
                val newImport = source.importMedia(request).getOrThrow()
                val imported = checkNotNull(source.indexedEntry(newImport.resourceUrls.single()))
                assertEquals("alternate", preferences.getBookshelfAssignments()[imported.itemKey])
                source.importMedia(request.copy(existingSeriesId = series.url)).getOrThrow()
                assertTrue(root.resolve("Renamed/02.epub").isFile)
                val seriesKey = checkNotNull(source.indexedEntry(series.url)).itemKey
                assertEquals("alternate", preferences.getBookshelfAssignments()[seriesKey])
                assertEquals(2, source.getChapterList(renamed.toSManga()).size)
                assertTrue(chapters.getChapterByMangaId(renamed.id).single().read)
            }
        }
    }

    @Test
    fun virtualComicMigrationRequiresOwnershipAndContainerDisplaySurvivesRename() = runBlocking(Dispatchers.IO) {
        for (storage in LocalMetadataStorage.entries) {
            fixture(storage) { root, source, preferences ->
                for (name in listOf("Mixed", "Pure")) {
                    val folder = root.resolve(name).apply { mkdirs() }
                    folder.resolve("001.png").writeBytes(image())
                    folder.resolve("002.png").writeBytes(image())
                    folder.resolve("ComicInfo.xml").writeText(comicInfo("Legacy $name", "Legacy author"))
                }
                root.resolve("Mixed/Child").mkdirs()
                source.refreshLibrary().getOrThrow()
                val entries = source.browseIndexedLibrary("")
                val container = entries.single { source.indexedEntry(it.url)?.kind == LocalLibraryItem.Kind.FOLDER }
                val pure = entries.single { source.indexedEntry(it.url)?.imageComic == true }
                assertEquals("Legacy Pure", pure.title)
                val mangas = repository.getMangaBySourceId(source.id)
                val comic = source.browseIndexedLibrary(mangas, "", parentUrl = container.url)
                    .single { source.indexedEntry(it.url)?.imageComic == true }
                assertEquals("Mixed", comic.title)
                assertNull(comic.author)
                val legacy = checkNotNull(source.legacyMetadataSuggestion(comic.url))
                assertEquals("Legacy Mixed", legacy.metadata.title)
                assertEquals(MetadataSuggestionSource.LEGACY_SIDECAR, legacy.fieldSources[LibraryMetadataField.TITLE])
                source.updateMetadata(comic.url, legacy.metadata).getOrThrow()
                repository.update(
                    MangaUpdate(
                        id = container.id,
                        author = "Stale author",
                        artist = "Stale artist",
                        description = "Stale description",
                        genre = listOf("Stale tag"),
                        status = 2L,
                        notes = "Directory notes",
                    ),
                )
                val containerKey = checkNotNull(source.indexedEntry(container.url)).itemKey
                preferences.setMetadataOverride(
                    containerKey,
                    LocalMetadataOverride(title = "Old pollution", author = "Pollution"),
                )
                source.refreshLibrary().getOrThrow()
                val cleaned = repository.getMangaById(container.id)
                assertNull(cleaned.author)
                assertNull(cleaned.artist)
                assertNull(cleaned.description)
                assertTrue(cleaned.genre.isNullOrEmpty())
                assertEquals("Directory notes", cleaned.notes)
                source.updateMetadata(
                    container.url,
                    LibraryMetadata(
                        title = "Display alias",
                        editedFields = setOf(LibraryMetadataField.TITLE),
                    ),
                ).getOrThrow()
                assertTrue(root.resolve("Mixed").renameTo(root.resolve("Renamed")))
                source.refreshLibrary().getOrThrow()
                assertEquals("Display alias", repository.getMangaById(container.id).title)
                assertEquals("Legacy Mixed", source.getMangaDetails(comic.toSManga()).title)
                assertEquals("Legacy author", source.getMangaDetails(comic.toSManga()).author)
                assertTrue(root.resolve("Renamed/ComicInfo.xml").readText().contains("Legacy Mixed"))
                assertTrue(root.resolve("Pure/ComicInfo.xml").exists())
                val child = root.resolve("Renamed/Child").canonicalFile
                check(child.parentFile == root.resolve("Renamed").canonicalFile)
                assertTrue(child.deleteRecursively())
                source.refreshLibrary().getOrThrow()
                assertEquals("Renamed", source.indexedEntry(comic.url)?.physicalPath())
                assertEquals("Legacy Mixed", source.getMangaDetails(comic.toSManga()).title)
            }
        }
    }
}
