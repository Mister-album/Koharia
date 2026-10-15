package koharia.reader

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionPageList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChapterCacheReaderLeaseDeviceTest {
    @Test fun cachedPagesSurviveClearUntilBothReadersClose() = exercise(cached = true)

    @Test fun fetchedPagesSurviveClearUntilBothReadersClose() = exercise(cached = false)

    private fun exercise(cached: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "chapter-cache-reader-${System.nanoTime()}").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        try {
            val isolated = object : ContextWrapper(context) {
                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
                override fun getExternalFilesDir(
                    type: String?,
                ) = File(root, "external/${type.orEmpty()}").apply { mkdirs() }
            }
            val bytes = ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).let { bitmap ->
                    try {
                        bitmap.eraseColor(Color.BLUE)
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val source = ImageSource(bytes)
            val cache = ChapterCache(isolated, Json)
            val chapter = Chapter.create().copy(id = 103, mangaId = 103, url = "/fixture", name = "Fixture")
            val url = "https://fixture.invalid/active.png"
            val idleUrl = "https://fixture.invalid/idle.png"
            val manual = File(root, "downloads/page.png").apply {
                check(parentFile!!.mkdirs())
                writeBytes(bytes)
            }
            val firstChapter = ReaderChapter(chapter)
            val secondChapter = ReaderChapter(chapter)
            val first = HttpPageLoader(firstChapter, source, cache)
            val second = HttpPageLoader(secondChapter, source, cache)
            try {
                cache.putPageListToCache(chapter, ConnectionPageList(listOf(Page(0, imageUrl = url))))
                if (cached) cache.putImageToCache(source.id, url, source.response(url))
                cache.putImageToCache(source.id, idleUrl, source.response(idleUrl))
                val firstPages = first.getPages()
                firstChapter.state = ReaderChapter.State.Loaded(firstPages)
                firstPages.forEach { it.chapter = firstChapter }
                first.setActivePage(firstPages.single())
                withTimeout(5000) { firstPages.single().statusFlow.first { it == Page.State.Ready } }
                val secondPages = second.getPages()
                secondChapter.state = ReaderChapter.State.Loaded(secondPages)
                secondPages.forEach { it.chapter = secondChapter }
                second.setNetworkRequestsDeferred(true)
                second.setActivePage(secondPages.single())
                withTimeout(5000) { secondPages.single().statusFlow.first { it == Page.State.Ready } }
                val page = secondPages.single()
                checkNotNull(page.stream).invoke().use { heldStream ->
                    cache.clear()
                    assertFalse(cache.isImageInCache(source.id, idleUrl))
                    assertTrue("Clear must preserve a ready reader's image", cache.isImageInCache(source.id, url))
                    assertArrayEquals(bytes, heldStream.readBytes())
                    first.recycle()
                    first.recycle()
                    cache.clear()
                    assertTrue(cache.isImageInCache(source.id, url))
                    checkNotNull(page.stream).invoke().use { stream ->
                        val bitmap = checkNotNull(BitmapFactory.decodeStream(stream))
                        try {
                            assertEquals(Color.BLUE, bitmap.getPixel(40, 60))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                second.recycle()
                second.recycle()
                cache.clear()
                assertFalse(cache.isImageInCache(source.id, url))
                assertArrayEquals(bytes, manual.readBytes())
                assertEquals(if (cached) 0 else 1, source.requests.get())
            } finally {
                first.recycle()
                second.recycle()
                // Closing the isolated cache also prevents late detached page-list writes.
                val disk = ChapterCache::class.java.getDeclaredField("diskCache").apply { isAccessible = true }
                    .get(cache) as DiskLruCache
                disk.close()
            }
        } finally {
            check(root.canonicalFile.parentFile == parent)
            root.deleteRecursively()
        }
    }

    private class ImageSource(private val bytes: ByteArray) : HttpSource() {
        override val name = "Cache fixture"
        override val lang = "en"
        override val baseUrl = "https://fixture.invalid"
        override val supportsLatest = false
        val requests = AtomicInteger()

        fun response(url: String) = Response.Builder().request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()

        override suspend fun getImage(page: Page): Response {
            requests.incrementAndGet()
            return response(checkNotNull(page.imageUrl))
        }

        override fun popularMangaRequest(page: Int): Nothing = error("Not used")
        override fun popularMangaParse(response: Response): Nothing = error("Not used")
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Nothing = error("Not used")
        override fun searchMangaParse(response: Response): Nothing = error("Not used")
        override fun latestUpdatesRequest(page: Int): Nothing = error("Not used")
        override fun latestUpdatesParse(response: Response): Nothing = error("Not used")
        override fun mangaDetailsParse(response: Response): Nothing = error("Not used")
        override fun chapterListParse(response: Response): Nothing = error("Not used")
        override fun chapterPageParse(response: Response): Nothing = error("Not used")
        override fun pageListParse(response: Response): Nothing = error("Not used")
        override fun imageUrlParse(response: Response): Nothing = error("Not used")
    }
}
