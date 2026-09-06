package koharia.source.local

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalFileDeletionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private val root get() = uni(fixture)
    private fun uni(file: File): UniFile = checkNotNull(UniFile.fromFile(file))

    @Before
    fun setup() {
        fixture = Files.createTempDirectory(context.cacheDir.toPath(), "local-delete-test-").toFile().canonicalFile
    }

    @After
    fun cleanup() {
        check(fixture.parentFile == context.cacheDir.canonicalFile)
        fixture.deleteRecursively()
    }

    private fun write(path: String, text: String = "test book"): File = fixture.resolve(path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun prepare(path: String, series: Boolean = false) = LocalFileDeletion.prepare(
        root,
        path,
        series,
        setOf(localDeletionIdentity(root)),
    )

    @Test
    fun preparingAndCancellingDoesNotDeleteAnything() {
        val book = write("book.txt")
        val neighbor = write("other.txt")
        assertEquals(1, prepare("book.txt").fileCount)
        assertTrue(book.exists())
        assertTrue(neighbor.exists())
    }

    @Test
    fun deletesOnlySelectedFileAndPreservesSidecars() {
        val book = write("book.txt")
        val neighbor = write("other.txt")
        val sidecar = write("book.metadata.opf")
        prepare("book.txt").delete()
        assertFalse(book.exists())
        assertTrue(neighbor.exists())
        assertTrue(sidecar.exists())
    }

    @Test
    fun rootImageEntryPreservesRootBooksAndNestedImages() {
        val image = write("1.png")
        val book = write("book.txt")
        val nested = write("nested/2.png")
        prepare(LocalLibraryLocator.ROOT_DIRECTORY_ENTRY).delete()
        assertFalse(image.exists())
        assertTrue(fixture.isDirectory)
        assertTrue(book.exists())
        assertTrue(nested.exists())
    }

    @Test
    fun seriesIncludesOnlyConfirmedDirectoryTree() {
        write("series/1.cbz")
        write("series/images/1.png")
        write("series/ComicInfo.xml")
        val neighbor = write("other/2.cbz")
        val plan = prepare("series", series = true)
        assertEquals(3, plan.fileCount)
        plan.delete()
        assertFalse(fixture.resolve("series").exists())
        assertTrue(neighbor.exists())
    }

    @Test
    fun refusesRootTraversalAndNestedLibraryRoots() {
        write("series/nested/book.txt")
        assertTrue(runCatching { prepare(LocalLibraryLocator.ROOT_DIRECTORY_ENTRY, series = true) }.isFailure)
        assertTrue(runCatching { prepare("../outside.txt") }.isFailure)
        assertTrue(
            runCatching {
                LocalFileDeletion.prepare(
                    root,
                    "series",
                    true,
                    setOf(localDeletionIdentity(uni(fixture.resolve("series/nested")))),
                )
            }.isFailure,
        )
        assertTrue(fixture.resolve("series/nested/book.txt").exists())
    }

    @Test
    fun refusesSymlinkEscapeEvenWhenChangedAfterConfirmation() {
        val outside = write("outside/book.txt")
        fixture.resolve("library").mkdirs()
        val library = uni(fixture.resolve("library"))
        val book = write("library/book.txt")
        val plan = LocalFileDeletion.prepare(library, "book.txt", false, emptySet())
        assertTrue(book.delete())
        Files.createSymbolicLink(book.toPath(), outside.toPath())
        assertTrue(runCatching { plan.delete() }.isFailure)
        assertTrue(runCatching { LocalFileDeletion.prepare(library, "book.txt", false, emptySet()) }.isFailure)
        assertTrue(outside.exists())
        Files.delete(book.toPath())
    }

    @Test
    fun refusesChangedFilesAndPreservesNewFiles() {
        val changed = write("book.txt")
        val bookPlan = prepare("book.txt")
        changed.appendText("changed")
        assertTrue(runCatching { bookPlan.delete() }.isFailure)
        assertTrue(changed.exists())
        write("series/1.txt")
        val seriesPlan = prepare("series", series = true)
        val added = write("series/new.txt")
        assertTrue(runCatching { seriesPlan.delete() }.isFailure)
        assertTrue(added.exists())
    }

    @Test
    fun permissionLossDoesNotReportFilesAsDeleted() {
        val book = write("book.txt")
        val plan = prepare("book.txt")
        try {
            assertTrue(fixture.setReadable(false, false))
            assertTrue(runCatching { plan.delete() }.isFailure)
            assertTrue(book.exists())
        } finally {
            assertTrue(fixture.setReadable(true, true))
        }
    }

    @Test
    fun configuredRootAliasIsSupportedButRetargetingItIsRejected() {
        val book = write("library/book.txt")
        val alias = fixture.resolve("alias")
        Files.createSymbolicLink(alias.toPath(), fixture.resolve("library").toPath())
        try {
            val plan = LocalFileDeletion.prepare(uni(alias), "book.txt", false, emptySet())
            plan.delete()
            assertFalse(book.exists())
            write("library/book.txt")
            val changedPlan = LocalFileDeletion.prepare(uni(alias), "book.txt", false, emptySet())
            val other = write("other/book.txt")
            Files.delete(alias.toPath())
            Files.createSymbolicLink(alias.toPath(), fixture.resolve("other").toPath())
            assertTrue(runCatching { changedPlan.delete() }.isFailure)
            assertTrue(other.exists())
        } finally {
            Files.delete(alias.toPath())
        }
    }

    @Test
    fun grantedSafTestTreeSupportsSingleFilesImagesAndSeries() {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.isWritePermission && it.isReadPermission &&
                DocumentsContract.getTreeDocumentId(it.uri)
                    .startsWith("primary:Download/KohariaLocalLibraryTest/")
        }
        assumeTrue("Requires a previously granted, dedicated SAF test library", permission != null)
        val granted = checkNotNull(permission)
        val base = checkNotNull(UniFile.fromUri(context, granted.uri))
        val name = "local-delete-test-${UUID.randomUUID()}"
        val directory = checkNotNull(base.createDirectory(name))
        val expectedId = "${DocumentsContract.getTreeDocumentId(granted.uri)}/$name"
        check(DocumentsContract.getDocumentId(directory.uri) == expectedId)
        fun writeTo(parent: UniFile, filename: String): UniFile = checkNotNull(parent.createFile(filename)).also {
            it.openOutputStream().use { output -> output.write("test".toByteArray()) }
        }
        fun plan(path: String, series: Boolean = false) = LocalFileDeletion.prepare(
            directory,
            path,
            series,
            setOf(localDeletionIdentity(directory)),
        )
        try {
            val book = writeTo(directory, "book.txt")
            val other = writeTo(directory, "other.txt")
            plan("book.txt").delete()
            assertFalse(book.exists())
            assertTrue(other.exists())
            val image = writeTo(directory, "1.png")
            val nested = checkNotNull(directory.createDirectory("series"))
            writeTo(nested, "1.cbz")
            plan(LocalLibraryLocator.ROOT_DIRECTORY_ENTRY).delete()
            assertFalse(image.exists())
            assertTrue(other.exists())
            assertTrue(nested.exists())
            plan("series", series = true).delete()
            assertFalse(nested.exists())
            assertTrue(directory.exists())
            assertTrue(other.exists())
        } finally {
            check(DocumentsContract.getDocumentId(directory.uri) == expectedId)
            assertTrue(directory.delete())
        }
    }

    @Test
    fun batchDeletionReconcilesDatabaseAndKeepsFailedEntries() = runBlocking(Dispatchers.IO) {
        val sourceId = 9_100_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val source = LocalFolderSource(
            context,
            sourceId,
            "Deletion test",
            LibraryConnectionProfile(sourceId, "local-folder", "Deletion test"),
        )
        try {
            write("one.txt")
            val changed = write("two.txt")
            val neighbor = write("three.txt")
            preferences.setConfig(
                LocalLibraryConfig(
                    roots = listOf(
                        LocalLibraryRootConfig(
                            id = "test-root",
                            treeUri = fixture.toURI().toString(),
                            contentType = LocalLibraryContentType.BOOKS,
                            bookshelfId = "test-shelf",
                        ),
                    ),
                    bookshelves = listOf(
                        LocalBookshelf(
                            "test-shelf",
                            "Test",
                            LocalLibraryContentType.BOOKS,
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                        ),
                    ),
                ),
            )
            source.refreshLibrary().getOrThrow()
            val all = mangas.getMangaBySourceId(sourceId)
            assertEquals(3, all.size)
            val one = all.first { it.url.endsWith("one.txt") }
            val two = all.first { it.url.endsWith("two.txt") }
            val chapter = chapters.addAll(listOf(Chapter.create().copy(mangaId = one.id, url = one.url))).single()
            val key = LocalLibraryLocator.itemKey("test-root", "one.txt")
            preferences.setMetadataOverride(key, LocalMetadataOverride(title = "custom title"))
            preferences.setBookshelfAssignment(key, "test-shelf")
            val plan = source.prepareFileDeletion(listOf(one, two))
            changed.appendText("changed after confirmation")
            val result = source.deleteLocalFiles(plan)
            assertEquals(listOf(one.id), result.deleted.map { it.id })
            assertEquals(listOf(two.id), result.failed.map { it.id })
            assertFalse(fixture.resolve("one.txt").exists())
            assertTrue(changed.exists())
            assertTrue(neighbor.exists())
            assertEquals(2, mangas.getMangaBySourceId(sourceId).size)
            assertNull(chapters.getChapterById(chapter.id))
            assertFalse(key in preferences.getMetadataOverrides())
            assertFalse(key in preferences.getBookshelfAssignments())
            source.refreshLibrary().getOrThrow()
            assertEquals(2, source.browseIndexedLibrary(query = "").size)
            val remaining = mangas.getMangaBySourceId(sourceId)
            val stalePlan = source.prepareFileDeletion(remaining)
            val config = preferences.getConfig()
            preferences.setConfig(config.copy(roots = config.roots.map { it.copy(displayPath = "changed") }))
            assertTrue(runCatching { source.deleteLocalFiles(stalePlan) }.isFailure)
            assertTrue(changed.exists())
            assertTrue(neighbor.exists())
        } finally {
            mangas.deleteMangaBySourceId(sourceId)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
        }
    }
}
