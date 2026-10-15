package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.LocalCacheCleaner
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionRegistry
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePreferences
import koharia.pdf.cache.PdfReflowCacheManager
import koharia.testing.SafSourceReaders
import koharia.testing.SystemSafFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DownloadCacheBoundaryDeviceTest {
    @Test
    fun clearingIndexAndDeletingOneDownloadPreservesOtherReaderAndContentCaches() = exercise()

    @Test
    fun completeEpubCacheAndManualPublicationHaveIndependentDownloadCounts() = exercise(completePublication = true)

    @Test
    fun systemSafClearAndSingleDownloadDeletionPreserveSourceReadersAndOtherDownload() =
        SystemSafFixture.withTree { exercise(it) }

    private fun exercise(safTree: UniFile? = null, completePublication: Boolean = false): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "download-boundary-${System.nanoTime()}").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(
                type: String?,
            ) = File(root, "external/${type.orEmpty()}").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("${root.name}-$name".also { preferenceNames += it }, mode)
        }
        val scopes = mutableListOf<Any>()
        var chapterCache: ChapterCache? = null
        var reader: DownloadPageLoader? = null
        val cleanupJobs = mutableListOf<Job>()
        var network: NetworkHelper? = null
        var publicationCache: EpubCacheManager? = null
        var publicationClient: OkHttpClient? = null
        try {
            val store = InMemoryPreferenceStore()
            val connectionPreferences = ConnectionPreferences(store, Json)
            val registry = ConnectionRegistry(emptyList())
            val folders = object : FolderProvider {
                override fun directory() = root
                override fun path() = (safTree ?: UniFile.fromFile(root)!!).uri.toString()
            }
            val storage = StorageManager(isolated, StoragePreferences(folders, store)).also { scopes += it }
            val provider =
                DownloadProvider(isolated, storage, LibraryPreferences(store), connectionPreferences, registry)
            val publication = if (completePublication) epubBytes() else byteArrayOf()
            val directorySource = DirectorySource()
            val source = if (completePublication) {
                object : DirectorySource(), ConnectionRawDownloadAdapter {
                    override val rawDownloadClient = OkHttpClient.Builder().addInterceptor { chain ->
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("Owned publication")
                            .body(publication.toResponseBody("application/epub+zip".toMediaType())).build()
                    }.build().also { publicationClient = it }

                    override fun rawFileRequest(resourceUrl: String, rangeStart: Long?) =
                        Request.Builder().url("https://fixture.invalid/owned.epub").build()
                }
            } else {
                directorySource
            }
            val sources = object : SourceManager {
                override val isInitialized = MutableStateFlow(true)
                override val catalogueSources = flowOf(emptyList<eu.kanade.tachiyomi.source.CatalogueSource>())
                override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }
                override fun getOrStub(sourceKey: Long) = checkNotNull(get(sourceKey))
                override fun getOnlineSources() = listOf(source)
                override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
                override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
            }
            val cache = DownloadCache(isolated, provider, sources, storage, connectionPreferences, registry)
                .also { scopes += it }
            val pageCache = ChapterCache(isolated, Json).also { chapterCache = it }
            val application = object : Application() {
                override fun getFilesDir() = isolated.filesDir
                override fun getCacheDir() = isolated.cacheDir
                override fun getExternalFilesDir(type: String?) = isolated.getExternalFilesDir(type)
            }
            val epub = EpubCacheManager(application, EpubCachePreferences(store))
                .also {
                    publicationCache = it
                    cleanupJobs += scopeJob(it, "cleanupScope")
                }
            val reflow = PdfReflowCacheManager(application).also { cleanupJobs += scopeJob(it, "cleanupScope") }
            val http = NetworkHelper(isolated, NetworkPreferences(store)).also { network = it }
            val cleaner = LocalCacheCleaner(isolated, pageCache, CoverCache(isolated), cache, http, epub, reflow)
            val downloadPreferences = DownloadPreferences(store)
            val downloader = Downloader(isolated, provider, cache, sources, pageCache, downloadPreferences)
                .also { scopes += it }
            val manager = DownloadManager(
                context = isolated,
                provider = provider,
                cache = cache,
                sourceManager = sources,
                downloadPreferences = downloadPreferences,
                downloader = downloader,
                pendingDeleter = DownloadPendingDeleter(isolated),
            ).also { scopes += it }
            val manga = Manga.create().copy(id = Long.MIN_VALUE + 104, source = source.id, title = "Boundary fixture")
            val removed = Chapter.create().copy(
                id = Long.MIN_VALUE + 105,
                mangaId = manga.id,
                name = "First",
                url = "/1",
            )
            val retained = removed.copy(id = Long.MIN_VALUE + 106, name = "Second", url = "/2")
            val publicationChapter = removed.copy(id = Long.MIN_VALUE + 107, name = "Publication", url = "/book.epub")
            val publicationKey = "owned-publication"
            if (completePublication) {
                assertEquals(0, manager.getDownloadCount(manga))
                val cached = checkNotNull(epub.cacheCompleteBook(source, publicationChapter.url, publicationKey))
                assertArrayEquals(publication, cached.readBytes())
                ZipFile(cached).use { archive ->
                    assertEquals(
                        "application/epub+zip",
                        archive.getInputStream(archive.getEntry("mimetype")).reader().readText(),
                    )
                    checkNotNull(archive.getEntry("book.opf"))
                    checkNotNull(archive.getEntry("chapter.xhtml"))
                }
                assertTrue(epub.hasCompleteBook(source.id, publicationKey))
                assertEquals("Complete publication cache is not a manual download", 0, manager.getDownloadCount(manga))
            }
            val image = ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLUE)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
            val archive = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use {
                    it.putNextEntry(ZipEntry("001.png"))
                    it.write(image)
                    it.closeEntry()
                }
                output.toByteArray()
            }
            val mangaDir = provider.getMangaDir(manga.title, source).getOrThrow()
            check(mangaDir.uri.scheme == if (safTree == null) "file" else "content")
            fun create(chapter: Chapter) = checkNotNull(
                mangaDir.createFile(provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz"),
            ).also { file ->
                checkNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(archive) }
            }
            fun UniFile.readBytes() = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
            val first = create(removed)
            val second = create(retained)
            val manualPublication = if (completePublication) {
                val name = provider.getChapterDirName(publicationChapter.name, null, publicationChapter.url) + ".epub"
                val file = checkNotNull(mangaDir.createFile(name))
                checkNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(publication) }
                for (download in listOf(
                    first,
                    second,
                    file,
                )) {
                    cache.addChapter(checkNotNull(download.name), mangaDir, manga)
                }
                assertEquals(3, manager.getDownloadCount(manga))
                assertArrayEquals(publication, file.readBytes())
                file
            } else {
                null
            }
            val idleNetworkBlock = File(isolated.filesDir, "library-storage-cache/fixture/resource/0.block").apply {
                check(parentFile!!.mkdirs())
                writeBytes(image)
            }
            val separate = listOf("library-storage-cache/content.bin", "epub_cache/book.epub", "source/original.cbz")
                .map { name ->
                    File(root, name).apply {
                        parentFile!!.mkdirs()
                        writeBytes(archive)
                    }
                }
            val loader = DownloadPageLoader(ReaderChapter(retained), manga, source, manager, provider).also {
                reader =
                    it
            }
            suspend fun clearAndDelete() {
                val page = loader.getPages().single()
                checkNotNull(page.stream).invoke().use { heldStream ->
                    cache.clearDiskCache()
                    assertArrayEquals(archive, first.readBytes())
                    assertArrayEquals(archive, second.readBytes())
                    cleaner.clearAllTemporaryCache()
                    assertFalse("All temporary caches must include idle network content", idleNetworkBlock.exists())
                    assertArrayEquals(archive, first.readBytes())
                    assertArrayEquals(archive, second.readBytes())
                    if (completePublication) {
                        assertFalse(epub.hasCompleteBook(source.id, publicationKey))
                        assertArrayEquals(publication, checkNotNull(manualPublication).readBytes())
                        withTimeout(5000) {
                            while (manager.getDownloadCount(manga) != 3) delay(25)
                        }
                        assertEquals(
                            "Cache clearing preserves all manual downloads",
                            3,
                            manager.getDownloadCount(manga),
                        )
                    }
                    manager.deleteChapters(listOf(removed), manga, source)
                    withTimeout(5000) { scopeJob(manager).children.toList().joinAll() }
                    assertFalse("Only the requested manual download should be removed", first.exists())
                    assertArrayEquals(archive, second.readBytes())
                    if (completePublication) {
                        assertEquals(2, manager.getDownloadCount(manga))
                        val cached =
                            checkNotNull(epub.cacheCompleteBook(source, publicationChapter.url, publicationKey))
                        assertArrayEquals(publication, cached.readBytes())
                        manager.deleteChapters(listOf(publicationChapter), manga, source)
                        withTimeout(5000) { scopeJob(manager).children.toList().joinAll() }
                        assertFalse(checkNotNull(manualPublication).exists())
                        assertArrayEquals(publication, cached.readBytes())
                        assertEquals(
                            "Deleting a manual EPUB keeps its separate cached publication",
                            1,
                            manager.getDownloadCount(manga),
                        )
                        assertTrue(epub.hasCompleteBook(source.id, publicationKey))
                    }
                    assertArrayEquals(image, heldStream.readBytes())
                    checkNotNull(page.stream).invoke().use { stream ->
                        val bitmap = checkNotNull(BitmapFactory.decodeStream(stream))
                        try {
                            assertEquals(Color.BLUE, bitmap.getPixel(40, 60))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            if (safTree == null) {
                clearAndDelete()
            } else {
                SafSourceReaders.whileOpen(context, safTree, reflow, ::clearAndDelete)
            }
            assertEquals(
                second.uri,
                provider.findChapterDir(
                    retained.name,
                    retained.scanlator,
                    retained.url,
                    manga.title,
                    source,
                )?.uri,
            )
            separate.forEach { assertArrayEquals(archive, it.readBytes()) }
            assertTrue(manager.queueState.value.isEmpty())
            assertFalse(manager.isRunning)
        } finally {
            reader?.recycle()
            scopes.asReversed().forEach { scopeJob(it).cancelAndJoin() }
            cleanupJobs.forEach { it.cancelAndJoin() }
            publicationCache?.let { ownCache ->
                val dispatchers = EpubCacheManager::class.java.getDeclaredField("transferDispatchers")
                    .apply { isAccessible = true }.get(ownCache) as Map<*, *>
                dispatchers.values.filterIsInstance<okhttp3.Dispatcher>().forEach { it.executorService.shutdown() }
            }
            publicationClient?.let {
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            network?.client?.let {
                it.cache?.close()
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            chapterCache?.let {
                val disk = ChapterCache::class.java.getDeclaredField("diskCache").apply { isAccessible = true }
                    .get(it) as DiskLruCache
                disk.close()
            }
            preferenceNames.forEach { check(context.deleteSharedPreferences(it)) }
            check(root.canonicalFile.parentFile == parent)
            check(root.deleteRecursively())
        }
    }

    private fun scopeJob(target: Any, field: String = "scope") = checkNotNull(
        (target.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(target) as CoroutineScope)
            .coroutineContext[Job],
    )

    private fun epubBytes(): ByteArray {
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
            "book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">owned-cache-boundary</dc:identifier><dc:title>Owned publication</dc:title>
                <dc:language>en</dc:language><meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                </metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
            """.trimIndent(),
            "chapter.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Owned</title></head>
                <body><p>Independent cache and manual publication.</p></body></html>
            """.trimIndent(),
            "nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
                <li><a href="chapter.xhtml">Owned</a></li></ol></nav></body></html>
            """.trimIndent(),
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, text) ->
                    val bytes = text.encodeToByteArray()
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 1577836800000L
                            if (name == "mimetype") {
                                method = ZipEntry.STORED
                                size = bytes.size.toLong()
                                compressedSize = size
                                crc = CRC32().apply { update(bytes) }.value
                            }
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private open class DirectorySource : HttpSource(), ConnectionDownloadStorageAdapter {
        override val id = Long.MIN_VALUE + 104
        override val name = "Boundary fixture"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://fixture.invalid"
        override fun popularMangaRequest(page: Int): Request = error("No catalogue requests")
        override fun popularMangaParse(response: Response): Nothing = error("No catalogue responses")
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            error("No catalogue requests")
        override fun searchMangaParse(response: Response): Nothing = error("No catalogue responses")
        override fun latestUpdatesRequest(page: Int): Request = error("No catalogue requests")
        override fun latestUpdatesParse(response: Response): Nothing = error("No catalogue responses")
        override fun mangaDetailsParse(response: Response): Nothing = error("No catalogue responses")
        override fun chapterListParse(response: Response): Nothing = error("No catalogue responses")
        override fun chapterPageParse(response: Response): Nothing = error("No catalogue responses")
        override fun pageListParse(response: Response): Nothing = error("No catalogue responses")
        override fun imageUrlParse(response: Response): Nothing = error("No catalogue responses")
        override val usesSharedDownloadStorage = false
        override fun downloadDirectoryName() = "boundary-source"
        override fun downloadDirectoryNames() = listOf(downloadDirectoryName())
        override fun ownedDownloadDirectoryNames() = setOf(downloadDirectoryName())
        override fun legacyDownloadDirectoryNames() = emptyList<String>()
    }
}
