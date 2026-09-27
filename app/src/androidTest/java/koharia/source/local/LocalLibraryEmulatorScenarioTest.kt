@file:Suppress("ktlint:standard:max-line-length")

package koharia.source.local

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import eu.kanade.tachiyomi.ui.main.MainActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.EntryOpenMode
import koharia.connection.EntryOpenPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.epub.EpubReaderLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Opt-in, retained libraries for emulator performance measurements and manual UI validation. */
@RunWith(AndroidJUnit4::class)
class LocalLibraryEmulatorScenarioTest {
    @Test
    fun damagedZipDoesNotBlockHealthySafEntries() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assumeTrue(InstrumentationRegistry.getArguments().getString("scenarioDamage") == "true")
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator"))
        val connections: ConnectionPreferences = Injekt.get()
        val profile = connections.getProfiles().single { it.id == connections.activeConnectionId.get() }
        check(profile.name == "拟真测试 60 ZIP + 12 Books")
        val preferences = LocalLibraryPreferences(profile.id, Json)
        val root = preferences.getConfig().roots.single { it.id == "comics" }
        check(root.treeUri.contains("Koharia-Fixture-20260927"))
        val directory = checkNotNull(preferences.resolveRoot(context, root))
        val name = "damaged-fixture-${System.nanoTime()}.zip"
        val file = checkNotNull(directory.createFile(name))
        val source = LocalFolderSource(context, profile.id, profile.name, profile)
        val report = File(context.getExternalFilesDir(null), "emulator-library-20260927/damaged-zip.txt")
        try {
            file.openOutputStream().use { it.write("Intentionally invalid ZIP fixture".toByteArray()) }
            val start = SystemClock.elapsedRealtime()
            source.refreshLibrary().getOrThrow()
            val books = source.browseIndexedLibrary("")
            report.writeText("scanWithDamagedZipMs=${SystemClock.elapsedRealtime() - start} entries=${books.size}\n")
            assertEquals(73, books.size)
            assertEquals(72, books.count { !it.url.endsWith(name) })
            val damaged = books.single { it.url.endsWith(name) }
            assertTrue(source.loadChapterThumbnail(checkNotNull(damaged.thumbnailUrl)) == null)
            assertTrue(
                source.loadChapterThumbnail(
                    checkNotNull(
                        books.first {
                            it.title == "测试漫画 000"
                        }.thumbnailUrl,
                    ),
                ) != null,
            )
        } finally {
            assertTrue(file.delete())
            val repository: MangaRepository = Injekt.get()
            repository.getMangaBySourceId(profile.id).filter { it.title.startsWith("damaged-fixture-") }.forEach {
                repository.deleteMangaById(it.id)
            }
            source.refreshLibrary().getOrThrow()
        }
        assertEquals(72, source.browseIndexedLibrary("").size)
        assertTrue(context.getSharedPreferences("source_${profile.id}", 0).edit().commit())
        report.appendText("PASS - healthy entries and cover retained; damaged fixture removed\n")
    }

    @Test
    fun measureRetainedLibraryThroughSystemSaf() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assumeTrue(InstrumentationRegistry.getArguments().getString("scenarioSaf") == "true")
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator"))
        val tree = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents/Koharia-Fixture-20260927",
        )
        val report = File(context.getExternalFilesDir(null), "emulator-library-20260927/saf-measurements.txt")
        report.writeText("System ExternalStorageProvider SAF\n")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUriUsingTree(
                                tree,
                                "primary:Documents/Koharia-Fixture-20260927",
                            ),
                        )
                    },
                    9127,
                )
            }
            withTimeout(180_000) {
                while (runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }.isFailure) {
                    delay(500)
                }
            }
        }
        val connections: ConnectionPreferences = Injekt.get()
        val profile = connections.getProfiles().single { it.id == connections.activeConnectionId.get() }
        check(profile.name == "拟真测试 60 ZIP + 12 Books")
        val preferences = LocalLibraryPreferences(profile.id, Json)
        val config = preferences.getConfig()
        check(config.roots.all { it.id in setOf("comics", "books") })
        preferences.setConfig(
            config.copy(
                roots = config.roots.map {
                    it.copy(
                        treeUri = tree.toString(),
                        relativePath = it.id,
                        displayPath = "Documents/Koharia-Fixture-20260927/${it.id}",
                    )
                },
            ),
        )
        val repository: MangaRepository = Injekt.get()
        repository.deleteMangaBySourceId(profile.id)
        preferences.setIndex(LocalLibraryIndex())
        val source = LocalFolderSource(context, profile.id, profile.name, profile)
        suspend fun timed(label: String, action: suspend () -> Unit) {
            val start = SystemClock.elapsedRealtime()
            action()
            report.appendText("$label ms=${SystemClock.elapsedRealtime() - start}\n")
        }
        timed("safInitialScan") { source.refreshLibrary().getOrThrow() }
        assertEquals(72, repository.getMangaBySourceId(profile.id).size)
        repeat(3) { timed("safUnchangedRefresh-$it") { source.refreshLibrary().getOrThrow() } }
        repeat(3) { timed("safIndexedBrowse-$it") { assertEquals(72, source.browseIndexedLibrary("").size) } }
        report.appendText("PASS\n")
        assertTrue(context.getSharedPreferences("source_${profile.id}", 0).edit().commit())
    }

    @Test
    fun createAndMeasureRetainedLibraries() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assumeTrue(InstrumentationRegistry.getArguments().getString("createLocalScenario") == "true")
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator"))
        val directory = File(context.getExternalFilesDir(null), "emulator-library-20260927").apply { mkdirs() }
        val report = File(directory, "measurements.txt")
        report.writeText("model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n")
        fun record(value: String) {
            report.appendText("$value\n")
        }
        suspend fun <T> timed(label: String, action: suspend () -> T): T {
            val start = SystemClock.elapsedRealtime()
            return action().also { record("$label ms=${SystemClock.elapsedRealtime() - start}") }
        }
        val comics = File(directory, "comics").apply { mkdirs() }
        val books = File(directory, "books").apply { mkdirs() }
        timed("generate") {
            val images = (0..7).map { pageImage(it) }
            repeat(60) { number ->
                val file = File(comics, "分组${number / 20}/测试漫画 ${number.toString().padStart(3, '0')}.zip")
                if (!file.exists()) {
                    file.parentFile!!.mkdirs()
                    ZipOutputStream(file.outputStream().buffered()).use { zip ->
                        repeat(120) { page ->
                            zip.entry("章节/page-${page.toString().padStart(4, '0')}.jpg", images[page % images.size])
                        }
                        if (number % 3 == 0) {
                            zip.entry(
                                "ComicInfo.xml",
                                "<ComicInfo><Title>测试漫画 $number</Title><Writer>Fixture author</Writer></ComicInfo>".toByteArray(),
                            )
                        }
                        repeat(20) { zip.entry("metadata/$it.json", "{\"page\":$it}".toByteArray()) }
                    }
                }
                if (number % 10 == 9) record("generatedComics=${number + 1}")
            }
            repeat(3) { number ->
                File(
                    books,
                    "测试文档 $number.txt",
                ).writeText(("Chapter $number\nThis is a local reading fixture. 中文段落与标点测试。\n").repeat(500))
                File(
                    books,
                    "测试笔记 $number.md",
                ).writeText(("# Chapter $number\n\n**Bold**, *italic*, and 中文 Markdown。\n\n").repeat(100))
                writeEpub(File(books, "测试书籍 $number.epub"), number)
                val pdf = PdfDocument()
                try {
                    repeat(20) { page ->
                        val sheet = pdf.startPage(PdfDocument.PageInfo.Builder(600, 900, page + 1).create())
                        sheet.canvas.drawColor(Color.WHITE)
                        val paint = Paint().apply {
                            color = Color.BLACK
                            textSize = 20f
                        }
                        repeat(25) { line ->
                            sheet.canvas.drawText(
                                "Book $number / page $page / line $line",
                                30f,
                                40f + line * 30,
                                paint,
                            )
                        }
                        pdf.finishPage(sheet)
                    }
                    File(books, "测试排版 $number.pdf").outputStream().use(pdf::writeTo)
                } finally {
                    pdf.close()
                }
            }
        }
        record(
            "files=${directory.walkTopDown().count {
                it.isFile
            }} bytes=${directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }}",
        )
        val connections: ConnectionPreferences = Injekt.get()
        val sourceId = connections.getProfiles().singleOrNull {
            it.name == "拟真测试 60 ZIP + 12 Books" &&
                LocalLibraryPreferences(it.id, Json).getConfig().roots.any { root ->
                    root.treeUri ==
                        comics.toURI().toString()
                }
        }?.id ?: connections.allocateConnectionId()
        val profile = LibraryConnectionProfile(sourceId, LocalFolderConnectionProvider.ID, "拟真测试 60 ZIP + 12 Books")
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val base = LocalLibraryConfig().withInitialBookshelves("漫画拟真库", "书籍拟真库")
        preferences.setConfig(
            base.copy(
                setupCompleted = true,
                bookshelves = base.bookshelves.map {
                    it.copy(organizationMode = LocalLibraryOrganizationMode.INDIVIDUAL_FILES)
                },
                roots = listOf(
                    LocalLibraryRootConfig(
                        "comics",
                        comics.toURI().toString(),
                        comics.path,
                        LocalLibraryContentType.COMICS,
                        base.defaultBookshelfId(LocalLibraryContentType.COMICS),
                    ),
                    LocalLibraryRootConfig(
                        "books",
                        books.toURI().toString(),
                        books.path,
                        LocalLibraryContentType.BOOKS,
                        base.defaultBookshelfId(LocalLibraryContentType.BOOKS),
                    ),
                ),
            ),
        )
        connections.setProfiles(connections.getProfiles().filterNot { it.id == sourceId } + profile)
        connections.activeConnectionId.set(sourceId)
        record("sourceId=$sourceId")
        val source = LocalFolderSource(context, sourceId, profile.name, profile)
        timed("initialScan") { source.refreshLibrary().getOrThrow() }
        val repository: MangaRepository = Injekt.get()
        val mangas = repository.getMangaBySourceId(sourceId)
        assertEquals(72, mangas.size)
        repeat(3) { timed("unchangedRefresh-$it") { source.refreshLibrary().getOrThrow() } }
        repeat(3) {
            timed("indexedBrowse-$it") { assertEquals(72, source.browseIndexedLibrary("").size) }
        }
        assertEquals(12, mangas.count { source.isIndividualBookEntry(it.url) })
        assertTrue(mangas.all { source.isIndividualFileEntry(it.url) })
        repeat(2) { pass ->
            timed("allCovers-pass$pass-memoryDisabled") {
                mangas.chunked(4).forEach { batch ->
                    coroutineScope {
                        batch.map { manga ->
                            async {
                                val result = context.imageLoader.execute(
                                    ImageRequest.Builder(context).data(manga)
                                        .memoryCachePolicy(
                                            CachePolicy.DISABLED,
                                        ).size(240, 360).allowHardware(false).build(),
                                )
                                record("cover pass=$pass title=${manga.title} result=${result.javaClass.simpleName}")
                                assertTrue("Cover failed: ${manga.title}: $result", result is SuccessResult)
                            }
                        }.awaitAll()
                    }
                }
            }
        }
        val opening: EntryOpenPreferences = Injekt.get()
        val oldBook = opening.localSingleBook.get()
        val oldComic = opening.localSingleComic.get()
        try {
            opening.localSingleBook.set(EntryOpenMode.READER.name)
            opening.localSingleComic.set(EntryOpenMode.DETAILS.name)
            assertEquals(EntryOpenMode.READER, opening.localBookMode())
            assertEquals(EntryOpenMode.DETAILS, opening.localMode())
            val launcher = EpubReaderLauncher()
            val manager = LocalLibraryEntryOpenManager(Injekt.get(), Injekt.get(), launcher)
            for (extension in listOf("zip", "epub", "pdf", "txt", "md")) {
                val manga = mangas.first { it.url.endsWith(".$extension") }
                timed("prepareReader-$extension") {
                    val chapter = manager.prepareChapter(source, manga)
                    val intent = launcher.resolveIntent(context, manga.id, chapter.id)
                    record("reader $extension component=${intent.component?.className}")
                    assertTrue(intent.component != null)
                }
            }
        } finally {
            opening.localSingleBook.set(oldBook)
            opening.localSingleComic.set(oldComic)
        }
        record("PASS - retained generated libraries, profiles, application and data")
    }

    private fun pageImage(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 34f
                strokeWidth = 4f
            }
            val random = Random(seed)
            repeat(6) { panel ->
                val left = 25f + (panel % 2) * 490
                val top = 60f + (panel / 2) * 470
                paint.style = Paint.Style.STROKE
                canvas.drawRect(left, top, left + 460, top + 440, paint)
                repeat(350) {
                    val x = left + random.nextInt(440)
                    val y = top + random.nextInt(420)
                    canvas.drawLine(x, y, x + random.nextInt(20), y + random.nextInt(20), paint)
                }
                paint.style = Paint.Style.FILL
                canvas.drawText("Panel $panel / $seed", left + 15, top + 50, paint)
            }
            return ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeEpub(file: File, number: Int) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.entry("mimetype", "application/epub+zip".toByteArray())
            zip.entry("cover.jpg", pageImage(number))
            zip.entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            zip.entry(
                "content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture-$number</dc:identifier><dc:title>测试书籍 $number</dc:title><dc:language>zh</dc:language><meta name="cover" content="cover"/></metadata><manifest><item id="cover" href="cover.jpg" media-type="image/jpeg"/><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="chapter"/></spine></package>""".toByteArray(),
            )
            zip.entry(
                "toc.ncx",
                """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="fixture-$number"/></head><docTitle><text>Test book</text></docTitle><navMap><navPoint id="ch" playOrder="1"><navLabel><text>Chapter</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap></ncx>""".toByteArray(),
            )
            val paragraphs = "<p>这是测试正文。 Local library reading, punctuation and paragraph layout.</p>".repeat(300)
            zip.entry(
                "chapter.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><h1>测试章节</h1>$paragraphs</body></html>""".toByteArray(),
            )
        }
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
