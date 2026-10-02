package koharia.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class LocalMetadataStoreTest {
    @Test
    fun `individual entries never fall back to a generic parent sidecar in any storage mode`() {
        LocalMetadataStorage.entries.forEach { storage ->
            val fixture = Fixture(storage)
            val content = fixture.store.opf(LocalMetadataOverride(title = "Parent metadata"))
            fixture.root.put("metadata.opf", content)
            fixture.metadataDirectory().put("metadata.opf", content)
            assertNull(
                fixture.store.readAndMigrate("book", fixture.root.file, LocalLibraryContentType.BOOKS, "Only.epub"),
                storage.name,
            )
            assertNull(
                fixture.store.externalMetadata("book", fixture.root.file, LocalLibraryContentType.BOOKS, "Only.epub"),
                storage.name,
            )
        }
    }

    @Test
    fun `metadata save and read remain isolated by role in all storage modes`() {
        LocalMetadataStorage.entries.forEach { storage ->
            val fixture = Fixture(storage)
            for ((key, entry, role) in listOf(
                Triple("series", null, LocalMetadataRole.SERIES),
                Triple("images", null, LocalMetadataRole.FOLDER_IMAGE_SERIES),
                Triple("book", "Only.epub", LocalMetadataRole.INDIVIDUAL_FILE),
            )) {
                assertTrue(
                    fixture.store.save(
                        key,
                        LocalMetadataOverride(title = key),
                        fixture.root.file,
                        LocalLibraryContentType.BOOKS,
                        entry?.substringBeforeLast('.'),
                        entry,
                        role,
                    ),
                )
                val read = if (storage == LocalMetadataStorage.DATABASE) {
                    fixture.overrides[key]
                } else {
                    fixture.store.readAndMigrate(key, fixture.root.file, LocalLibraryContentType.BOOKS, entry, role)
                }
                assertEquals(key, read?.title, storage.name)
            }
            assertFalse(
                fixture.store.save(
                    "container",
                    LocalMetadataOverride(title = "Pollution"),
                    fixture.root.file,
                    role = LocalMetadataRole.FOLDER_CONTAINER,
                ),
            )
            assertFalse(
                fixture.store.save(
                    "chapter",
                    LocalMetadataOverride(title = "Pollution"),
                    fixture.root.file,
                    role = LocalMetadataRole.CHAPTER,
                ),
            )
            assertNull(
                fixture.store.readAndMigrate(
                    "series",
                    fixture.root.file,
                    LocalLibraryContentType.BOOKS,
                    role = LocalMetadataRole.FOLDER_CONTAINER,
                ),
            )
        }
    }

    private class Directory {
        val files = mutableMapOf<String, Entry>()
        val directories = mutableMapOf<String, Directory>()
        val file: UniFile = mockk()
        var failRenameTo: String? = null
        init {
            every { file.findFile(any()) } answers {
                files[firstArg<String>()]?.file ?: directories[firstArg<String>()]?.file
            }
            every { file.createDirectory(any()) } answers {
                directories.getOrPut(firstArg()) { Directory() }.file
            }
            every { file.createFile(any()) } answers { put(firstArg(), "").file }
        }
        fun put(name: String, value: String): Entry = Entry(name, value).also { files[name] = it }
        inner class Entry(var name: String, var content: String) {
            val file: UniFile = mockk()
            init {
                every { file.name } answers { name }
                every { file.openInputStream() } answers { content.byteInputStream() }
                every { file.openOutputStream() } answers {
                    object : ByteArrayOutputStream() {
                        override fun close() {
                            content = toString(Charsets.UTF_8.name())
                        }
                    }
                }
                every { file.renameTo(any()) } answers {
                    val target = firstArg<String>()
                    if (target == failRenameTo || target in files) {
                        false
                    } else {
                        files.remove(name)
                        name = target
                        files[name] = this@Entry
                        true
                    }
                }
                every { file.delete() } answers { files.remove(name) != null }
            }
        }
    }

    private class Fixture(val storage: LocalMetadataStorage) {
        val root = Directory()
        val context: Context = mockk()
        val preferences: LocalLibraryPreferences = mockk()
        val overrides = mutableMapOf<String, LocalMetadataOverride>()
        val revisions = mutableMapOf<String, String>()
        val store: LocalMetadataStore
        init {
            every { preferences.getConfig() } returns LocalLibraryConfig(metadataStorage = storage)
            every { preferences.metadataBaseDirectory(context) } returns root.file
            every { preferences.getMetadataOverrides() } answers { overrides.toMap() }
            every { preferences.setMetadataOverride(any(), any()) } answers { overrides[firstArg()] = secondArg() }
            every { preferences.metadataRevision(any()) } answers { revisions[firstArg()] }
            every { preferences.setMetadataRevision(any(), any()) } answers { revisions[firstArg()] = secondArg() }
            store = LocalMetadataStore(
                context,
                1,
                Json,
                XML {
                    defaultPolicy { ignoreUnknownChildren() }
                    autoPolymorphic = true
                    xmlDeclMode = XmlDeclMode.Charset
                    indent = 2
                    xmlVersion = XmlVersion.XML10
                },
                preferences,
            )
        }
        fun metadataDirectory(): Directory = root.directories.getOrPut(".koharia") { Directory() }
            .directories.getOrPut("metadata") { Directory() }
    }

    @Test
    fun `OPF preserves contributors subjects and escaped Unicode text`() {
        val fixture = Fixture(LocalMetadataStorage.DATABASE)
        val value =
            LocalMetadataOverride(
                title =
                "A & <书>",
                author =
                "作者",
                artist =
                "译者",
                description =
                "a < b & c",
                genres =
                listOf(
                    "科幻",
                    "A&B",
                ),
            )
        val parsed = parseLocalOpfMetadata(Jsoup.parse(fixture.store.opf(value), "", Parser.xmlParser()))
        assertEquals(value.title, parsed.title)
        assertEquals(listOf("译者"), parsed.contributors)
        assertEquals(value.genres, parsed.subjects)
        assertEquals(value.description, parsed.description)
    }

    @Test
    fun `replacement keeps previous external metadata and verifies new contents`() {
        val directory = Directory()
        directory.put("metadata.opf", "old")
        assertTrue(writeRecoverableLocalFile(directory.file, "metadata.opf", "new"))
        assertEquals("new", directory.files.getValue("metadata.opf").content)
        assertTrue(directory.files.values.any { it.name.endsWith(".bak") && it.content == "old" })
    }

    @Test
    fun `failed promotion preserves old file in recoverable backup`() {
        val directory = Directory()
        directory.put("metadata.opf", "old")
        directory.failRenameTo = "metadata.opf"
        assertFalse(writeRecoverableLocalFile(directory.file, "metadata.opf", "new"))
        assertTrue(directory.files.values.any { it.content == "old" })
    }

    @Test
    fun `legacy JSON migrates to OPF retaining original and application-only fields`() {
        val fixture = Fixture(LocalMetadataStorage.UNIFIED_DIRECTORY)
        val value =
            LocalMetadataOverride(
                title =
                "Book",
                artist =
                "Translator",
                genres =
                listOf("Fiction"),
                status =
                2,
                lockedFields =
                setOf("title"),
            )
        val directory = fixture.metadataDirectory()
        directory.put("key.json", Json.encodeToString(value))
        assertEquals(value, fixture.store.readAndMigrate("key", null, LocalLibraryContentType.BOOKS))
        assertNotNull(directory.files["key.json"])
        assertNotNull(directory.files["key.metadata.opf"])
        assertEquals(value, fixture.overrides["key"])
        assertEquals("Book", fixture.store.readAndMigrate("key", null, LocalLibraryContentType.BOOKS)?.title)
    }

    @Test
    fun `migration does not overwrite existing standard metadata`() {
        val fixture = Fixture(LocalMetadataStorage.UNIFIED_DIRECTORY)
        val directory = fixture.metadataDirectory()
        directory.put("key.json", Json.encodeToString(LocalMetadataOverride(title = "Legacy")))
        directory.put("key.metadata.opf", fixture.store.opf(LocalMetadataOverride(title = "External")))
        assertEquals("External", fixture.store.readAndMigrate("key", null, LocalLibraryContentType.BOOKS)?.title)
        assertTrue(directory.files["key.metadata.opf"]!!.content.contains("External"))
    }

    @Test
    fun `full filenames keep same-stem files and folder metadata separate`() {
        val fixture = Fixture(LocalMetadataStorage.FOLDER_DIRECTORY)
        for (name in listOf("Book.epub", "Book.pdf", null)) {
            assertTrue(
                fixture.store.save(
                    name ?: "folder",
                    LocalMetadataOverride(
                        title =
                        name ?: "Folder",
                    ),
                    fixture.root.file,
                    LocalLibraryContentType.BOOKS,
                    entryName =
                    name,
                ),
            )
        }
        assertEquals(
            setOf(
                "Book.epub.metadata.opf",
                "Book.pdf.metadata.opf",
                "metadata.opf",
            ),
            fixture.metadataDirectory().files.keys,
        )
        assertEquals(
            "Book.pdf",
            fixture.store.readAndMigrate(
                "new-id",
                fixture.root.file,
                LocalLibraryContentType.BOOKS,
                "Book.pdf",
            )?.title,
        )
    }

    @Test
    fun `virtual image series metadata uses a dedicated sidecar name`() {
        val fixture = Fixture(LocalMetadataStorage.FOLDER_DIRECTORY)
        assertTrue(
            fixture.store.save(
                itemKey = "virtual",
                metadata = LocalMetadataOverride(title = "Images"),
                itemDirectory = fixture.root.file,
                contentType = LocalLibraryContentType.COMICS,
                role = LocalMetadataRole.FOLDER_IMAGE_SERIES,
            ),
        )
        assertTrue(fixture.metadataDirectory().files.containsKey(".koharia-image-series.ComicInfo.xml"))
        assertTrue(
            fixture.metadataDirectory().files
                .getValue(".koharia-image-series.ComicInfo.xml")
                .content
                .contains("Images"),
        )
        fixture.metadataDirectory().put(
            ".koharia-image-series.metadata.opf",
            fixture.store.opf(LocalMetadataOverride(title = "Images")),
        )
        assertEquals(
            "Images",
            fixture.store.readAndMigrate(
                key = "virtual",
                itemDirectory = fixture.root.file,
                type = LocalLibraryContentType.BOOKS,
                role = LocalMetadataRole.FOLDER_IMAGE_SERIES,
            )?.title,
        )
    }

    @Test
    fun `adjacent sidecar is read from the item directory`() {
        val fixture = Fixture(LocalMetadataStorage.ADJACENT_SIDECAR)
        fixture.root.put(
            "Book.metadata.opf",
            fixture.store.opf(LocalMetadataOverride(title = "Adjacent")),
        )

        assertEquals(
            "Adjacent",
            fixture.store.readAndMigrate(
                key = "book",
                itemDirectory = fixture.root.file,
                type = LocalLibraryContentType.BOOKS,
                entryName = "Book.epub",
            )?.title,
        )
    }

    @Test
    fun `ComicInfo output preserves publishing status`() {
        val fixture = Fixture(LocalMetadataStorage.FOLDER_DIRECTORY)
        assertTrue(
            fixture.store.save(
                itemKey = "book",
                metadata = LocalMetadataOverride(title = "Book", status = SManga.COMPLETED),
                itemDirectory = fixture.root.file,
                contentType = LocalLibraryContentType.COMICS,
                entryName = "Book.cbz",
            ),
        )

        val content = fixture.metadataDirectory().files.getValue("Book.cbz.ComicInfo.xml").content
        assertTrue(content.contains("PublishingStatusTachiyomi"))
        assertTrue(content.contains("Completed"))
    }

    @Test
    fun `folder container metadata is not read from comic sidecar`() {
        val fixture = Fixture(LocalMetadataStorage.FOLDER_DIRECTORY)
        fixture.metadataDirectory().put("ComicInfo.xml", fixture.store.opf(LocalMetadataOverride(title = "Wrong")))
        assertNull(
            fixture.store.externalMetadata(
                key = "folder",
                directory = fixture.root.file,
                type = LocalLibraryContentType.COMICS,
                entryName = null,
                role = LocalMetadataRole.FOLDER_CONTAINER,
            ),
        )
    }

    @Test
    fun `external modification requires choice without persisting unsaved application edits`() {
        val fixture = Fixture(LocalMetadataStorage.UNIFIED_DIRECTORY)
        assertTrue(
            fixture.store.save(
                "key",
                LocalMetadataOverride(
                    title =
                    "Original",
                ),
                contentType =
                LocalLibraryContentType.BOOKS,
            ),
        )
        val external = fixture.metadataDirectory().files.getValue("key.metadata.opf")
        external.content = fixture.store.opf(LocalMetadataOverride(title = "External"))
        assertThrows(LocalMetadataConflictException::class.java) {
            fixture.store.save(
                "key",
                LocalMetadataOverride(
                    title =
                    "Edited",
                ),
                contentType =
                LocalLibraryContentType.BOOKS,
            )
        }
        assertEquals("Original", fixture.overrides["key"]?.title)
        assertTrue(external.content.contains("External"))
        assertTrue(
            fixture.store.save(
                "key",
                LocalMetadataOverride(
                    title =
                    "Edited",
                ),
                contentType =
                LocalLibraryContentType.BOOKS,
                overwriteExternal =
                true,
            ),
        )
        assertTrue(fixture.metadataDirectory().files.getValue("key.metadata.opf").content.contains("Edited"))
    }

    @Test
    fun `accepting external metadata advances revision before the next edit`() {
        val fixture = Fixture(LocalMetadataStorage.UNIFIED_DIRECTORY)
        assertTrue(
            fixture.store.save(
                "key",
                LocalMetadataOverride(title = "Original"),
                contentType = LocalLibraryContentType.BOOKS,
            ),
        )
        val external = fixture.metadataDirectory().files.getValue("key.metadata.opf")
        external.content = fixture.store.opf(LocalMetadataOverride(title = "External"))

        assertEquals(
            "External",
            fixture.store.externalMetadata(
                key = "key",
                directory = null,
                type = LocalLibraryContentType.BOOKS,
                entryName = null,
                acceptExternalChanges = true,
            )?.title,
        )
        assertTrue(
            fixture.store.save(
                "key",
                LocalMetadataOverride(title = "Edited"),
                contentType = LocalLibraryContentType.BOOKS,
            ),
        )
        assertEquals("Edited", fixture.overrides["key"]?.title)
    }
}
