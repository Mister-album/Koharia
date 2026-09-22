package koharia.smanga

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run only with -PdeviceTestFixture=true; the HTTP server and every file belong to this test. */
@RunWith(AndroidJUnit4::class)
class SmangaPdfCacheTest {
    private lateinit var targetContext: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var server: PdfServer
    private lateinit var api: SmangaApi
    private lateinit var pdf: ByteArray
    private val account = "pdf-device-fixture".encodeUtf8().sha256().hex()
    private val key = "chapter-7".encodeUtf8().sha256().hex()

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", targetContext.packageName)
        root = File(targetContext.cacheDir, "smanga-pdf-device-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        context = object : ContextWrapper(targetContext) {
            override fun getCacheDir(): File = root
        }
        pdf = createPdf()
        server = PdfServer { Reply(pdf) }
        api = SmangaApi(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            Json,
            "http://127.0.0.1:${server.port}/fixture",
            "fixture-reader",
            "fixture-password",
            account,
        )
    }

    @After
    fun tearDown() {
        if (::api.isInitialized) api.close()
        if (::server.isInitialized) server.close()
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == targetContext.cacheDir.canonicalFile)
            check(root.name.startsWith("smanga-pdf-device-"))
            root.deleteRecursively()
        }
    }

    @Test
    fun preparePreservesOriginalAndRendersBothPages(): Unit = runBlocking(Dispatchers.IO) {
        val cache = cache()
        val file = withTimeout(10_000) { cache.prepare(key, 7, api) {} }
        assertArrayEquals(pdf, File(requireNotNull(file.uri.path)).readBytes())
        assertEquals(file.uri, cache.find(key)?.uri)
        assertFalse(partialFile().exists())
        assertRenders(File(requireNotNull(file.uri.path)))
        val loader = PdfPageLoader(context, file)
        try {
            assertEquals(2, loader.getPages().size)
            assertEquals(2, loader.progressPageCount)
        } finally {
            loader.recycle()
        }
        val request = server.requests.single()
        assertTrue(request.target.startsWith("/fixture/api/opds/chapter/7/download?"))
        assertTrue(request.target.contains("koharia=$account"))
        assertEquals(
            Credentials.basic("fixture-reader", "fixture-password", Charsets.UTF_8),
            request.headers["authorization"],
        )
        assertNull(request.headers["token"])
        assertNull(request.headers["range"])
    }

    @Test
    fun corruptPdfNeverPublishesACompleteCache(): Unit = runBlocking(Dispatchers.IO) {
        server.handler = { Reply("%PDF-1.7\nnot a valid document".toByteArray()) }
        val cache = cache()
        val failure = runCatching { withTimeout(10_000) { cache.prepare(key, 7, api) {} } }.exceptionOrNull()
        assertNotNull("PdfRenderer must reject a corrupt original", failure)
        assertNull(cache.find(key))
        assertFalse(partialFile().exists())
        assertFalse(completeFile().exists())
    }

    @Test
    fun truncatedBodyNeverPublishesOrLeavesPartialData(): Unit = runBlocking(Dispatchers.IO) {
        server.handler = { Reply(pdf.copyOf(pdf.size / 2), declaredLength = pdf.size) }
        val cache = cache()
        val failure = runCatching { withTimeout(10_000) { cache.prepare(key, 7, api) {} } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertNull(cache.find(key))
        assertFalse(partialFile().exists())
    }

    @Test
    fun cancellationWhileReadingRemovesPartialAndAllowsFreshRetry(): Unit = runBlocking(Dispatchers.IO) {
        val release = CountDownLatch(1)
        val partialWritten = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        server.handler = { Reply(pdf, gate = release, prefixBytes = pdf.size / 2) }
        val cache = cache()
        val preparing = async {
            cache.prepare(key, 7, api) {
                // The second loop check occurs after the first body chunk was written.
                if (checks.incrementAndGet() >= 3) partialWritten.complete(Unit)
            }
        }
        try {
            withTimeout(10_000) { partialWritten.await() }
            assertTrue(partialFile().length() > 0)
            withTimeout(10_000) { preparing.cancelAndJoin() }
            assertNull(cache.find(key))
            assertFalse(partialFile().exists())
        } finally {
            release.countDown()
        }
        server.handler = { Reply(pdf) }
        val retried = withTimeout(10_000) { cache().prepare(key, 7, api) {} }
        assertArrayEquals(pdf, File(requireNotNull(retried.uri.path)).readBytes())
        assertTrue(server.requests.all { it.headers["range"] == null })
    }

    @Test
    fun abandonedPartialFromPreviousInstanceRestartsWithCompleteOriginal(): Unit = runBlocking(Dispatchers.IO) {
        assertTrue(partialFile().parentFile!!.mkdirs())
        partialFile().writeBytes(pdf.copyOf(pdf.size / 3))
        val restarted = cache()
        assertNull(restarted.find(key))
        val file = withTimeout(10_000) { restarted.prepare(key, 7, api) {} }
        assertArrayEquals(pdf, File(requireNotNull(file.uri.path)).readBytes())
        assertFalse(partialFile().exists())
        assertNull(server.requests.single().headers["range"])
    }

    @Test
    fun freshCacheInstanceReusesVerifiedOriginalWithoutNetwork(): Unit = runBlocking(Dispatchers.IO) {
        val first = withTimeout(10_000) { cache().prepare(key, 7, api) {} }
        assertEquals(1, server.requests.size)
        server.close()
        val restarted = cache()
        assertEquals(first.uri, restarted.find(key)?.uri)
        val reused = withTimeout(10_000) { restarted.prepare(key, 7, api) {} }
        assertEquals(first.uri, reused.uri)
        assertEquals(1, server.requests.size)
        assertRenders(File(requireNotNull(reused.uri.path)))
    }

    private fun cache() = SmangaPdfCache(context, 7, account)
    private fun partialFile() = File(context.cacheDir, "smanga-pdf/7/$account/$key.part")
    private fun completeFile() = File(context.cacheDir, "smanga-pdf/7/$account/$key.pdf")

    private fun createPdf(): ByteArray {
        val document = PdfDocument()
        return try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(160, 240, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun assertRenders(file: File) {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals(2, renderer.pageCount)
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private data class Seen(val target: String, val headers: Map<String, String>)
    private data class Reply(
        val bytes: ByteArray,
        val declaredLength: Int = bytes.size,
        val gate: CountDownLatch? = null,
        val prefixBytes: Int = 0,
    )

    private class PdfServer(@Volatile var handler: (Seen) -> Reply) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val requests = CopyOnWriteArrayList<Seen>()
        val port: Int get() = listener.localPort

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: IOException) {
                        break
                    }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use { connection ->
                                connection.soTimeout = 10_000
                                val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                                val target = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: return@use
                                    if (line.isEmpty()) break
                                    headers[line.substringBefore(':').lowercase(Locale.ROOT)] =
                                        line.substringAfter(':').trim()
                                }
                                val request = Seen(target, headers)
                                requests += request
                                val reply = handler(request)
                                val output = connection.getOutputStream()
                                output.write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: application/pdf\r\n" +
                                            "Content-Length: ${reply.declaredLength}\r\nConnection: close\r\n\r\n"
                                        )
                                        .toByteArray(Charsets.US_ASCII),
                                )
                                if (reply.gate != null) {
                                    output.write(reply.bytes, 0, reply.prefixBytes)
                                    output.flush()
                                    if (!reply.gate.await(10, TimeUnit.SECONDS)) return@use
                                }
                                output.write(reply.bytes, reply.prefixBytes, reply.bytes.size - reply.prefixBytes)
                                output.flush()
                            }
                        } catch (_: IOException) {
                            // A cancelled client closes the body before the server finishes writing.
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } finally {
                            sockets -= socket
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
