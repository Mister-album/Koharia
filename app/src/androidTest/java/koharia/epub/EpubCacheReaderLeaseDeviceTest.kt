package koharia.epub

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubCacheReaderLeaseDeviceTest {
    @Test fun clearingWaitsForBothReadiumPublicationsAndPreservesManualFile(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val parent = context.cacheDir.canonicalFile
        val owned = File(parent, "epub-reader-lease-${System.nanoTime()}").canonicalFile
        check(owned.parentFile == parent && !owned.exists())
        val isolatedApplication = object : Application() {
            override fun getExternalFilesDir(type: String?) = File(owned, "external/${type.orEmpty()}")
            override fun getFilesDir() = File(owned, "files")
            override fun getCacheDir() = File(owned, "cache")
        }
        val manager = EpubCacheManager(isolatedApplication, EpubCachePreferences(InMemoryPreferenceStore()))
        try {
            val root = LocalTempCacheDirectoryProvider.epubCacheDir(isolatedApplication)
            val book = File(root, "books/41/fixture.epub").apply { check(parentFile!!.mkdirs()) }
            writeEpub(book)
            val manual = File(owned, "downloads/manual.epub").apply {
                check(parentFile!!.mkdirs())
                book.copyTo(this)
            }
            val original = manual.readBytes()
            val http = DefaultHttpClient()
            val retriever = AssetRetriever(context.contentResolver, http)
            val opener = PublicationOpener(
                DefaultPublicationParser(context.applicationContext as Application, http, retriever, null),
            )
            suspend fun open() = run {
                manager.acquire(book)
                try {
                    val asset = retriever.retrieve(
                        checkNotNull(Uri.fromFile(book).toAbsoluteUrl()),
                        MediaType.EPUB,
                    )
                        .getOrElse { error(it.message) }
                    opener.open(asset, allowUserInteraction = false).getOrElse {
                        asset.close()
                        error(it.message)
                    }
                } catch (error: Throwable) {
                    manager.release(book)
                    throw error
                }
            }
            val first = open()
            var firstClosed = false
            try {
                val second = open()
                try {
                    manager.clear()
                    assertTrue(book.isFile)
                    first.close()
                    firstClosed = true
                    manager.release(book)
                    manager.clear()
                    assertTrue("Second publication must keep the cache file", book.isFile)
                    val resource = checkNotNull(second.get(second.readingOrder.single()))
                    try {
                        val text = resource.read().getOrElse { error(it.message) }.decodeToString()
                        assertTrue(text.contains("Second reader remains readable"))
                    } finally {
                        resource.close()
                    }
                    assertTrue(original.contentEquals(manual.readBytes()))
                } finally {
                    second.close()
                    manager.release(book)
                }
            } finally {
                if (!firstClosed) {
                    first.close()
                    manager.release(book)
                }
            }
            withTimeout(5_000) { while (book.exists()) delay(10) }
            assertEquals(0, manager.sizeBytes())
            assertTrue(original.contentEquals(manual.readBytes()))
        } finally {
            val scope = EpubCacheManager::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
                .get(manager) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            check(owned.parentFile == parent)
            check(!owned.exists() || owned.deleteRecursively())
        }
    }

    private fun writeEpub(file: File) {
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent(),
            "book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">lease</dc:identifier>
                <dc:title>Lease fixture</dc:title><dc:language>en</dc:language></metadata><manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chapter"/></spine></package>
            """.trimIndent(),
            "chapter.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Lease</title></head>
                <body><p>Second reader remains readable</p></body></html>
            """.trimIndent(),
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                val bytes = text.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
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
    }
}
