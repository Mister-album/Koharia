package koharia.pdf

import android.app.Application
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.LocalEpubPublicationService
import koharia.epub.session.EpubReaderSession
import koharia.pdf.cache.PdfReflowCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfReflowReaderLeaseDeviceTest {
    @Test fun persistentReflowSurvivesClearUntilAllReaderReferencesClose() = exercise(ephemeral = false)

    @Test fun ephemeralReflowSurvivesClearUntilAllReaderReferencesClose() = exercise(ephemeral = true)

    private fun exercise(ephemeral: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val application = context.applicationContext as Application
        val parent = context.cacheDir.canonicalFile
        val root = File(parent, "pdf-reflow-reader-${System.nanoTime()}").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        val cache = PdfReflowCacheManager(application, File(root, "derived"))
        var first: EpubReaderSession? = null
        var second: EpubReaderSession? = null
        var retained = false
        try {
            val original = File(root, "original.pdf")
            val document = PdfDocument()
            try {
                repeat(12) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(300, 450, index).create())
                    val paint = Paint().apply { textSize = 16f }
                    repeat(10) { line ->
                        page.canvas.drawText("Fixture page $index line $line", 20f, 40f + line * 30, paint)
                    }
                    document.finishPage(page)
                }
                original.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val bytes = original.readBytes()
            val manual = File(root, "downloads/manual.pdf").apply {
                check(parentFile!!.mkdirs())
                writeBytes(bytes)
            }
            val artifact = cache.prepareProgressive(105, 106, UniFile.fromFile(original), "Fixture", 0, ephemeral, null)
            assertFalse(artifact.manifest.isComplete)
            assertEquals(listOf(0, 1, 2, 3), artifact.manifest.availablePages)
            val request = EpubOpenRequest(
                mangaId = 104,
                chapterId = 106,
                sourceId = 105,
                title = "Fixture",
                remotePublication = null,
                localUri = artifact.epub.toURI().toString(),
                openSource = EpubOpenRequest.OpenSource.LOCAL,
                pdfReflow = artifact.manifest,
            )
            val service = LocalEpubPublicationService(application, cache)
            first = service.open(request, null)
            second = service.open(request, null)
            assertTrue(second.retain())
            retained = true
            assertTrue(
                runCatching {
                    service.open(request.copy(localUri = File(root, "missing.epub").toURI().toString()), null)
                }.isFailure,
            )
            cache.clear()
            assertTrue(artifact.epub.isFile)
            first.close()
            first.close()
            cache.clear()
            assertTrue(artifact.epub.isFile)
            second.close()
            assertFalse(second.retain())
            cache.clear()
            assertTrue("The navigator still owns the closing session", artifact.epub.isFile)
            val resource =
                checkNotNull(second.publication.get(Link(href = checkNotNull(Url("EPUB/text/chunk-2.xhtml")))))
            try {
                val text = resource.read().getOrElse { error(it.message) }.decodeToString()
                assertTrue(text.contains("pdf-page-8"))
                assertTrue(text.contains("Fixture page 8"))
                assertTrue(8 in artifact.manifest.availablePages)
            } finally {
                resource.close()
            }
            second.release()
            retained = false
            second.close()
            if (ephemeral) {
                withTimeout(5000) { while (artifact.directory.exists()) delay(20) }
            } else {
                cache.clear()
            }
            assertFalse(artifact.directory.exists())
            assertArrayEquals(bytes, original.readBytes())
            assertArrayEquals(bytes, manual.readBytes())
        } finally {
            first?.close()
            second?.close()
            if (retained) second?.release()
            cache.clear()
            val scope = PdfReflowCacheManager::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
                .get(cache) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            check(root.canonicalFile.parentFile == parent)
            check(root.deleteRecursively())
        }
    }
}
