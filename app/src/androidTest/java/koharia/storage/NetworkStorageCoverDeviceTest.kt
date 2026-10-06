package koharia.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionRegistry
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryItem
import koharia.source.local.LocalLibraryLocator
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.NetworkStorageDraft
import koharia.source.local.saveNetworkLibraryDraft
import koharia.source.local.withInitialBookshelves
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetworkStorageCoverDeviceTest {
    @Test fun seriesCoversLoadFromArchivesAndImagesWithColdDirectoryCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            val server = if (mode ==
                LibraryStorageMode.SMB
            ) {
                "smb://127.0.0.1:18445/library"
            } else {
                "http://127.0.0.1:18765/"
            }
            val base = NetworkStorageConfiguration(mode = mode, address = server)
            val folder = "covers-${UUID.randomUUID()}"
            val parent = NetworkStorageRuntime.backend(base, server, "", "")
            parent.createDirectory(folder)
            val profile = manager.add("local-folder", "Refresh fixture")
            try {
                val draft = NetworkStorageDraft(base, "", "").withRoot(
                    if (mode == LibraryStorageMode.SMB) "library/$folder" else folder,
                )
                val saved = saveNetworkLibraryDraft(
                    context,
                    profile.id,
                    draft,
                    base,
                    LocalLibraryConfig().withInitialBookshelves("Comics", "Books").let { initial ->
                        initial.copy(
                            bookshelves = initial.bookshelves.map {
                                it.copy(organizationMode = koharia.source.local.LocalLibraryOrganizationMode.SERIES)
                            },
                        )
                    },
                    emptyMap(),
                    true,
                )
                assertEquals(setOf("Comics", "Books"), saved.roots.map { it.relativePath }.toSet())
                val provider = Injekt.get<ConnectionRegistry>().availableProviders().single { it.id == "local-folder" }
                val source = provider.createSource(profile) as LocalFolderSource
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                val png = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                    output.toByteArray()
                }
                val archive = ByteArrayOutputStream().use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("001.png"))
                        zip.write(png)
                        zip.closeEntry()
                    }
                    output.toByteArray()
                }
                parent.createDirectory("$folder/Comics/Archive series")
                parent.createDirectory("$folder/Comics/Image series")
                parent.write("$folder/Comics/Archive series/001.cbz", archive.inputStream(), archive.size.toLong())
                parent.write("$folder/Comics/Image series/001.png", png.inputStream(), png.size.toLong())
                val epub = ByteArrayOutputStream().use { output ->
                    ZipOutputStream(output).use { zip ->
                        val entries = mapOf(
                            "mimetype" to "application/epub+zip".toByteArray(),
                            "META-INF/container.xml" to """
<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="book.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>
                            """.trimIndent().toByteArray(),
                            "book.opf" to """
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">fixture</dc:identifier>
<dc:title>Fixture</dc:title>
<dc:language>en</dc:language>
<meta name="cover" content="cover"/>
</metadata>
<manifest>
<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
<item id="text" href="text.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine>
<itemref idref="text"/>
</spine>
</package>
                            """.trimIndent().toByteArray(),
                            "text.xhtml" to """
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<title>Fixture</title>
</head>
<body>
<p>Reading fixture.</p>
</body>
</html>
                            """.trimIndent().toByteArray(),
                            "cover.png" to png,
                        )
                        entries.forEach { (name, bytes) ->
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                    output.toByteArray()
                }
                val pdf = ByteArrayOutputStream().use { output ->
                    val document = android.graphics.pdf.PdfDocument()
                    try {
                        val page = document.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(32, 32, 1).create(),
                        )
                        page.canvas.drawColor(android.graphics.Color.WHITE)
                        document.finishPage(page)
                        document.writeTo(output)
                    } finally {
                        document.close()
                    }
                    output.toByteArray()
                }
                parent.createDirectory("$folder/Books/Epub series")
                parent.createDirectory("$folder/Books/Pdf series")
                parent.write("$folder/Books/Epub series/001.epub", epub.inputStream(), epub.size.toLong())
                parent.write("$folder/Books/Pdf series/001.pdf", pdf.inputStream(), pdf.size.toLong())
                source.refreshLibrary().getOrThrow()
                val local = LocalLibraryPreferences(profile.id, Injekt.get<Json>())
                val series = local.getIndex().items.filter { it.kind == LocalLibraryItem.Kind.SERIES }
                assertEquals(4, series.size)
                val runtime = NetworkStorageRuntime.get(context, profile.id)
                runtime.records.list("directories").filter { it.key.startsWith("Comics/") }.forEach {
                    runtime.records.remove("directories", it.key, it.revision)
                }
                for (item in series) {
                    val url = LocalLibraryLocator.entryUrl(profile.id, item.rootId, item.locatorPath)
                    val bytes = source.loadChapterThumbnail(url)
                    assertNotNull(bytes)
                    val decoded = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
                    assertNotNull(decoded)
                    assertTrue(decoded.width > 0)
                    decoded.recycle()
                    assertNotNull(source.loadSuggestedSeriesCover(url))
                }
                val chapters = local.getIndex().items.filter { it.kind == LocalLibraryItem.Kind.CHAPTER }
                assertTrue(chapters.size >= 3)
                for (chapter in chapters) {
                    val url = LocalLibraryLocator.entryUrl(profile.id, chapter.rootId, chapter.locatorPath)
                    assertTrue(chapter.locatorPath.startsWith(".koharia/nodes/"))
                    assertNotNull(source.prepareChapterFile(url))
                    assertNotNull(source.loadChapterThumbnail(url))
                }
                val comicsRoot = saved.roots.single { it.relativePath == "Comics" }
                assertNotNull(
                    source.loadChapterThumbnail(
                        LocalLibraryLocator.entryUrl(
                            profile.id,
                            comicsRoot.id,
                            LocalLibraryLocator.ROOT_DIRECTORY_ENTRY,
                        ),
                    ),
                )
            } finally {
                manager.remove(profile.id).getOrThrow()
                // Only descend into the unique directory created by this test.
                suspend fun removeOwned(path: String) {
                    parent.list(path).forEach { if (it.directory) removeOwned(it.path) else parent.delete(it) }
                    parent.delete(parent.stat(path))
                }
                removeOwned(folder)
                parent.close()
            }
        }
    }
}
