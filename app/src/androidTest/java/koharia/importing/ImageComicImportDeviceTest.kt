package koharia.importing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaImportRequest
import koharia.connection.LibraryConnectionProfile
import koharia.source.local.LocalBookshelf
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryContentType
import koharia.source.local.LocalLibraryLocator
import koharia.source.local.LocalLibraryOrganizationMode
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ImageComicImportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mergedComicPreservesPageOrderAndOriginalBytesAndImportsAsOneBook() = runBlocking(Dispatchers.IO) {
        withFixture { directory ->
            val red = png(File(directory, "red.png"), Color.RED)
            val blue = png(File(directory, "blue.png"), Color.BLUE)
            val output = ImageComicArchive.create(context, "Sample", listOf(item(blue), item(red)))
            val sourceId = 9_600_000_000_000L + System.currentTimeMillis()
            val preferences = LocalLibraryPreferences(sourceId, Json)
            val repository: MangaRepository = Injekt.get()
            try {
                ZipFile(output).use { zip ->
                    assertEquals(listOf("000001.png", "000002.png"), zip.entries().toList().map { it.name })
                    assertTrue(
                        zip.getInputStream(zip.getEntry("000001.png")).use {
                            it.readBytes()
                        }.contentEquals(blue.readBytes()),
                    )
                    assertTrue(
                        zip.getInputStream(zip.getEntry("000002.png")).use {
                            it.readBytes()
                        }.contentEquals(red.readBytes()),
                    )
                }
                val destinationDirectory = File(directory, "destination").apply { mkdirs() }
                preferences.setConfig(
                    LocalLibraryConfig(
                        setupCompleted = true,
                        enabledContentTypes = setOf(LocalLibraryContentType.COMICS),
                        bookshelves = listOf(
                            LocalBookshelf(
                                "comics",
                                "Comics",
                                LocalLibraryContentType.COMICS,
                                LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                            ),
                        ),
                        roots = listOf(
                            LocalLibraryRootConfig(
                                "root",
                                destinationDirectory.toURI().toString(),
                                contentType = LocalLibraryContentType.COMICS,
                                bookshelfId = "comics",
                            ),
                        ),
                    ),
                )
                val source =
                    LocalFolderSource(
                        context,
                        sourceId,
                        "Image comic test",
                        LibraryConnectionProfile(sourceId, "local-folder", "Image comic test"),
                    )
                val destination = source.mediaImportDestinations().single()
                assertFalse("png" in destination.supportedExtensions)
                assertTrue("cbz" in destination.supportedExtensions)
                assertTrue(
                    source.importMedia(ConnectionMediaImportRequest("root", "comics", "", listOf(item(red)))).isFailure,
                )
                assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
                val parsedComic = IncomingMediaParser.parse(context, listOf(output.toURI().toString())).single()
                assertEquals("cbz", parsedComic.extension)
                source.importMedia(ConnectionMediaImportRequest("root", "comics", "", listOf(parsedComic))).getOrThrow()
                assertEquals(1, source.browseIndexedLibrary(query = "").size)
                ImageComicArchive.deleteTemporary(context, output)
                assertFalse(output.exists())
                assertTrue(File(destinationDirectory, "Sample.cbz").isFile)
                assertTrue(red.exists() && blue.exists())
                val loose = File(destinationDirectory, "loose").apply { mkdirs() }
                red.copyTo(File(loose, "1.png"))
                blue.copyTo(File(loose, "2.png"))
                source.refreshLibrary().getOrThrow()
                assertTrue(source.browseIndexedLibrary(query = "").any { it.url.endsWith("/loose") })
                val cover = source.loadChapterThumbnail(LocalLibraryLocator.chapterUrl(sourceId, "root", "loose"))
                assertTrue(cover != null && cover.contentEquals(red.readBytes()))
            } finally {
                repository.deleteMangaBySourceId(sourceId)
                context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
                ImageComicArchive.deleteTemporary(context, output)
            }
        }
    }

    @Test
    fun singleCorruptAndCancelledImageMergesDoNotLeavePartialArchives() = runBlocking(Dispatchers.IO) {
        withFixture { directory ->
            val image = png(File(directory, "image.png"), Color.GREEN)
            val corrupt = File(directory, "corrupt.png").apply { writeText("Not an image") }
            val cache = File(context.cacheDir, "image-comic-import")
            val before = cache.list().orEmpty().toSet()
            assertTrue(runCatching { ImageComicArchive.create(context, "Single", listOf(item(image))) }.isFailure)
            assertTrue(
                runCatching {
                    ImageComicArchive.create(context, "Broken", listOf(item(image), item(corrupt)))
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    ImageComicArchive.create(context, "Cancelled", listOf(item(image), item(image))) {
                        throw CancellationException("Test cancellation")
                    }
                }.isFailure,
            )
            assertEquals(before, cache.list().orEmpty().toSet())
            val unrelated = File(directory, "original.cbz").apply { writeText("Keep") }
            ImageComicArchive.deleteTemporary(context, unrelated)
            assertEquals("Keep", unrelated.readText())
            assertTrue(image.exists())
        }
    }

    @Test
    fun renamedPhotoIsRecognizedAsImageBeforeDocumentImport() = runBlocking(Dispatchers.IO) {
        withFixture { directory ->
            val photo = png(File(directory, "photo.pdf"), Color.BLACK)
            assertEquals("png", IncomingMediaParser.parse(context, listOf(photo.toURI().toString())).single().extension)
        }
    }

    private fun item(file: File) = ConnectionMediaImportItem(
        file.toURI().toString(),
        file.name,
        "image/png",
        file.length(),
        "png",
    )

    private fun png(file: File, color: Int): File {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private inline fun withFixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "image-comic-test-").toFile()
        try {
            block(directory)
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
